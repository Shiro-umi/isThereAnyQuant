package org.shiroumi.strategy.service.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import model.Candle
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.ProductionOhlcvWindowRow
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.database.stock.TopListRepository
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.TargetPosition
import utils.logger
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val DEFAULT_TOP_N = 5
private const val DEFAULT_SEQ_LEN = 20
private const val DEFAULT_MARKET_GATED_SAMPLE_SIZE = 220
private const val DEFAULT_MIN_COVERAGE_RATIO = 0.85

internal class ProfitPredictionModelSelector(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val projectRoot: File = resolveProjectRoot(
        System.getProperty("quant.projectRoot") ?: System.getProperty("quant.project.root")
    ),
    private val modelDir: String = System.getProperty(
        "quant.profitPrediction.modelDir",
        "research/profit-prediction-v2/models/v3-honest-20260611"
    ),
    private val pythonWorkDir: String = System.getProperty(
        "quant.profitPrediction.pythonWorkDir",
        "strategy-server/research/pytorch"
    ),
    private val command: List<String> = System.getProperty(
        "quant.profitPrediction.command",
        "uv run quant-infer-v3"
    ).split(Regex("\\s+")).filter { it.isNotBlank() },
    // 训练侧特征在全历史上计算后切片(最长滚动窗 60 日)。推理取窗必须额外携带等长上下文,
    // 由推理服务在全窗上计算特征后取末 seqLen 行, 保证训练/推理特征数值一致。
    private val featureContextDays: Int = System.getProperty(
        "quant.profitPrediction.featureContextDays",
        "60"
    ).toInt(),
    private val timeoutSeconds: Long = System.getProperty("quant.profitPrediction.timeoutSeconds", "180").toLong(),
    private val topN: Int = System.getProperty("quant.profitPrediction.topN", DEFAULT_TOP_N.toString()).toInt(),
    private val seqLen: Int = System.getProperty("quant.profitPrediction.seqLen", DEFAULT_SEQ_LEN.toString()).toInt(),
    private val thresholdName: String = System.getProperty("quant.profitPrediction.thresholdName", "recall_0_2"),
    private val candidateMode: CandidateMode = CandidateMode.fromProperty(
        System.getProperty("quant.profitPrediction.candidateMode", CandidateMode.ALL_UNIVERSE.propertyValue)
    ),
    private val intradayCandidateMode: CandidateMode = CandidateMode.fromProperty(
        System.getProperty("quant.profitPrediction.intradayCandidateMode", CandidateMode.ALL_UNIVERSE.propertyValue)
    ),
    private val marketGatedSampleSize: Int = System.getProperty(
        "quant.profitPrediction.marketGatedSampleSize",
        DEFAULT_MARKET_GATED_SAMPLE_SIZE.toString()
    ).toInt(),
    private val minCoverageRatio: Double = System.getProperty(
        "quant.profitPrediction.minCoverageRatio",
        DEFAULT_MIN_COVERAGE_RATIO.toString()
    ).toDouble(),
    private val requireTopListCandidates: Boolean = System.getProperty(
        "quant.profitPrediction.requireTopListCandidates",
        "false"
    ).toBoolean(),
    private val dataSource: ProfitPredictionDataSource = DatabaseProfitPredictionDataSource(),
    private val processRunner: ProfitPredictionProcessRunner = DefaultProfitPredictionProcessRunner(),
) : ProfitPredictionTargetSelector {
    private val logger by logger("ProfitPredictionModelSelector")

    private val servicePort: Int = System.getProperty("quant.profitPrediction.servicePort", "9875").toInt()
    private val serviceUrl = "http://127.0.0.1:$servicePort"
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    companion object {
        @Volatile
        private var serviceProcess: Process? = null

        init {
            Runtime.getRuntime().addShutdownHook(Thread {
                synchronized(this) {
                    serviceProcess?.let {
                        if (it.isAlive) {
                            it.destroy()
                        }
                    }
                }
            })
        }
    }

    override suspend fun generateTargets(
        tradeDate: LocalDate,
        targetDate: LocalDate,
        universeSymbols: List<String>,
        sentiment: MarketSentimentSnapshot,
    ): List<TargetPosition> {
        validateDailyReadiness(tradeDate)
        val candidateSymbols = dataSource.loadCandidateSymbols(
            tradeDate = tradeDate,
            universeSymbols = universeSymbols,
            mode = candidateMode,
            marketGatedSampleSize = marketGatedSampleSize,
            requireTopListCandidates = requireTopListCandidates,
        )
        if (candidateSymbols.size < topN) {
            error(
                "profit prediction candidate pool too small tradeDate=$tradeDate mode=${candidateMode.propertyValue} " +
                    "candidates=${candidateSymbols.size} topN=$topN"
            )
        }

        val windows = dataSource.loadRecentOhlcvWindows(
            tsCodes = candidateSymbols,
            endDateInclusive = tradeDate,
            limitPerStock = seqLen + featureContextDays,
        )
        val request = buildRequest(tradeDate, candidateSymbols, windows)
        validateCoverage(tradeDate, request)
        val scored = infer(request)
        
        val rankedPredictions = scored.predictions
            .sortedWith(compareByDescending<ProfitPredictionOutput.Prediction> { it.score }.thenBy { it.tsCode })
        val selectedCodes = rankedPredictions
            .asSequence()
            .take(topN)
            .map { it.tsCode }
            .toSet()
        val weightPerStock = if (selectedCodes.isEmpty()) 0.0 else 1.0 / selectedCodes.size
        val exposure = sentiment.sentimentExposure
        logger.info(
            "[盈利预测选股] tradeDate=$tradeDate model=${scored.modelId} mode=${candidateMode.propertyValue} " +
                "candidates=${candidateSymbols.size} scored=${scored.coverage.scored} " +
                "skipped=${scored.coverage.skipped} selected=${selectedCodes.size} totalPositions=${rankedPredictions.size} " +
                "exposure=$exposure"
        )
        return rankedPredictions.map { prediction ->
            val selected = prediction.tsCode in selectedCodes
            TargetPosition(
                tradeDate = tradeDate,
                targetDate = targetDate,
                tsCode = prediction.tsCode,
                selectionScore = prediction.score,
                selected = selected,
                targetWeight = if (selected) weightPerStock else 0.0,
                sentimentExposure = exposure,
                selectionReason = "profit-prediction-v3:${scored.modelId}:${candidateMode.propertyValue}:" +
                    "${if (selected) "Top$topN" else "candidate"} score=${"%.6f".format(prediction.score)}",
            )
        }
    }

    override suspend fun generateIntradayTargets(
        tradeDate: LocalDate,
        universeSymbols: List<String>,
        realtimeDailyCandles: Map<String, Candle>,
        sentiment: MarketSentimentSnapshot,
    ): List<TargetPosition> {
        val previousTradeDate = dataSource.findPreviousTradingDate(tradeDate)
            ?: error("profit prediction intraday blocked: no previous trading date for $tradeDate")
        val candidateSymbols = dataSource.loadCandidateSymbols(
            tradeDate = tradeDate,
            universeSymbols = universeSymbols,
            mode = intradayCandidateMode,
            marketGatedSampleSize = marketGatedSampleSize,
            requireTopListCandidates = false,
        )
        if (candidateSymbols.size < topN) {
            error(
                "profit prediction intraday candidate pool too small tradeDate=$tradeDate " +
                    "mode=${intradayCandidateMode.propertyValue} candidates=${candidateSymbols.size} topN=$topN"
            )
        }

        val historicalWindows = dataSource.loadRecentOhlcvWindows(
            tsCodes = candidateSymbols,
            endDateInclusive = previousTradeDate,
            limitPerStock = seqLen - 1 + featureContextDays,
        )
        val windows = buildIntradayWindows(
            tradeDate = tradeDate,
            candidateSymbols = candidateSymbols,
            historicalWindows = historicalWindows,
            realtimeDailyCandles = realtimeDailyCandles,
        )
        val request = buildRequest(tradeDate, candidateSymbols, windows)
        validateCoverage(tradeDate, request)
        val scored = infer(request)
        
        val finalSelected = scored.predictions
            .asSequence()
            .filterNot { isLimitUp(tradeDate, it.tsCode, windows) }
            .sortedWith(compareByDescending<ProfitPredictionOutput.Prediction> { it.score }.thenBy { it.tsCode })
            .take(topN)
            .toList()

        val weightPerStock = if (finalSelected.isEmpty()) 0.0 else 1.0 / finalSelected.size
        val exposure = sentiment.sentimentExposure
        logger.info(
            "[盘中盈利预测选股] tradeDate=$tradeDate model=${scored.modelId} mode=${intradayCandidateMode.propertyValue} " +
                "candidates=${candidateSymbols.size} scored=${scored.coverage.scored} " +
                "skipped=${scored.coverage.skipped} selected=${finalSelected.size} exposure=$exposure"
        )
        return finalSelected.map { prediction ->
            TargetPosition(
                tradeDate = tradeDate,
                targetDate = tradeDate,
                tsCode = prediction.tsCode,
                selectionScore = prediction.score,
                selected = true,
                targetWeight = weightPerStock,
                sentimentExposure = exposure,
                selectionReason = "intraday-profit-prediction-v3:${scored.modelId}:${intradayCandidateMode.propertyValue}:Top$topN score=${"%.6f".format(prediction.score)}",
            )
        }
    }

    private fun isLimitUp(tradeDate: LocalDate, tsCode: String, windows: Map<String, List<ProductionOhlcvWindowRow>>): Boolean {
        val rows = windows[tsCode].orEmpty()
        if (rows.size < 2) return false
        val todayRow = rows.last()
        val prevRow = rows[rows.size - 2]
        if (todayRow.tradeDate != tradeDate) return false

        val limitPct = limitFor(tsCode)
        val upper = round2(prevRow.closeQfq * (1.0 + limitPct))
        return todayRow.closeQfq + 1e-6 >= upper
    }

    private fun limitFor(tsCode: String): Double {
        val code = tsCode.substringBefore(".")
        val market = tsCode.substringAfter(".", "")
        return when {
            market.equals("BJ", ignoreCase = true) -> 0.20
            code.startsWith("688") -> 0.20
            code.startsWith("300") -> 0.20
            code.startsWith("301") -> 0.20
            else -> 0.10
        }
    }

    private fun round2(v: Double): Double {
        return Math.round(v * 100.0) / 100.0
    }

    private fun validateDailyReadiness(tradeDate: LocalDate) {
        val readiness = dataSource.loadDailyReadiness(tradeDate)
        if (!readiness.stockDailyUpdated || !readiness.stockDailyFqUpdated) {
            error(
                "profit prediction blocked by incomplete daily data tradeDate=$tradeDate " +
                    "stockDailyUpdated=${readiness.stockDailyUpdated} stockDailyFqUpdated=${readiness.stockDailyFqUpdated}"
            )
        }
    }

    private fun buildRequest(
        tradeDate: LocalDate,
        universeSymbols: List<String>,
        windows: Map<String, List<ProductionOhlcvWindowRow>>,
    ): ProfitPredictionInput {
        val stocks = universeSymbols.mapNotNull { tsCode ->
            // 仅裁掉"最后一个未复权行"之前的上下文; 末段 seqLen 行的完整性要求与原先一致,
            // 上下文行不完整时按可用上下文优雅降级, 不影响覆盖率门槛。
            val fetched = windows[tsCode].orEmpty()
            val lastIncomplete = fetched.indexOfLast { !it.adjustedComplete }
            val rows = if (lastIncomplete >= 0) fetched.drop(lastIncomplete + 1) else fetched
            if (rows.size < seqLen || rows.lastOrNull()?.tradeDate != tradeDate) {
                return@mapNotNull null
            }
            ProfitPredictionInput.Stock(
                tsCode = tsCode,
                rows = rows.map {
                    ProfitPredictionInput.Row(
                        tradeDate = it.tradeDate.toString(),
                        openQfq = it.openQfq,
                        highQfq = it.highQfq,
                        lowQfq = it.lowQfq,
                        closeQfq = it.closeQfq,
                        volumeQfq = it.volumeQfq,
                        turnoverReal = it.turnoverReal,
                    )
                },
            )
        }
        return ProfitPredictionInput(
            tradeDate = tradeDate.toString(),
            universe = universeSymbols.sorted(),
            stocks = stocks,
        )
    }

    private fun buildIntradayWindows(
        tradeDate: LocalDate,
        candidateSymbols: List<String>,
        historicalWindows: Map<String, List<ProductionOhlcvWindowRow>>,
        realtimeDailyCandles: Map<String, Candle>,
    ): Map<String, List<ProductionOhlcvWindowRow>> {
        val result = linkedMapOf<String, List<ProductionOhlcvWindowRow>>()
        candidateSymbols.forEach { tsCode ->
            val fetched = historicalWindows[tsCode].orEmpty()
            val lastIncomplete = fetched.indexOfLast { !it.adjustedComplete }
            val historical = if (lastIncomplete >= 0) fetched.drop(lastIncomplete + 1) else fetched
            if (historical.size < seqLen - 1) {
                return@forEach
            }
            val realtime = realtimeDailyCandles[tsCode]?.toIntradayOhlcvWindowRow(tradeDate) ?: return@forEach
            result[tsCode] = historical.takeLast(seqLen - 1 + featureContextDays) + realtime
        }
        return result
    }

    private fun validateCoverage(tradeDate: LocalDate, request: ProfitPredictionInput) {
        val requested = request.universe.size
        val scored = request.stocks.size
        val ratio = if (requested == 0) 0.0 else scored.toDouble() / requested
        if (scored < topN || ratio + 1e-12 < minCoverageRatio) {
            error(
                "profit prediction blocked by insufficient model input coverage tradeDate=$tradeDate " +
                    "requested=$requested scored=$scored topN=$topN minCoverageRatio=$minCoverageRatio"
            )
        }
    }

    private suspend fun ensureServiceRunning(): Unit = withContext(Dispatchers.IO) {
        if (checkHealth()) {
            return@withContext
        }
        synchronized(ProfitPredictionModelSelector::class.java) {
            if (checkHealth()) {
                return@synchronized
            }
            serviceProcess?.let {
                if (it.isAlive) {
                    it.destroy()
                }
            }

            val resolvedModelDir = resolveConfiguredPath(modelDir, projectRoot)
            val resolvedPythonWorkDir = resolveConfiguredPath(pythonWorkDir, projectRoot)
            if (!resolvedModelDir.isDirectory) {
                error("profit prediction model dir not found: ${resolvedModelDir.absolutePath}")
            }
            if (!resolvedPythonWorkDir.isDirectory) {
                error("profit prediction python work dir not found: ${resolvedPythonWorkDir.absolutePath}")
            }
            if (command.isEmpty()) {
                error("profit prediction command is empty")
            }

            val serviceCommand = command + listOf(
                "--mode", "infer-service",
                "--model-dir", resolvedModelDir.absolutePath,
                "--threshold-name", thresholdName,
                "--port", servicePort.toString(),
            )

            logger.info("Starting Python inference service: ${serviceCommand.joinToString(" ")}")
            val builder = ProcessBuilder(serviceCommand)
                .directory(resolvedPythonWorkDir)
                .redirectErrorStream(true)

            val proc = builder.start()
            serviceProcess = proc

            Thread {
                try {
                    proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            logger.info("[Python Service] $line")
                        }
                    }
                } catch (e: Exception) {
                    logger.info("Python service output stream closed: ${e.message}")
                }
            }.start()
        }

        val start = System.currentTimeMillis()
        var healthy = false
        while (System.currentTimeMillis() - start < 15_000) {
            if (checkHealth()) {
                healthy = true
                break
            }
            kotlinx.coroutines.delay(500)
        }
        if (!healthy) {
            val exit = serviceProcess?.let { if (!it.isAlive) it.exitValue() else null }
            error("Python inference service failed to start or become healthy on port $servicePort. Exit code: $exit")
        }
        logger.info("Python inference service started successfully and is healthy.")
    }

    private fun checkHealth(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$serviceUrl/health"))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun infer(request: ProfitPredictionInput): ProfitPredictionOutput = withContext(Dispatchers.IO) {
        if (processRunner::class.java.simpleName != "DefaultProfitPredictionProcessRunner") {
            val resolvedModelDir = resolveConfiguredPath(modelDir, projectRoot)
            val resolvedPythonWorkDir = resolveConfiguredPath(pythonWorkDir, projectRoot)
            val fullCommand = command + listOf(
                "--mode", "infer-json",
                "--model-dir", resolvedModelDir.absolutePath,
                "--top-n", topN.toString(),
                "--threshold-name", thresholdName,
            )
            val result = processRunner.run(
                command = fullCommand,
                workingDir = resolvedPythonWorkDir,
                stdin = json.encodeToString(ProfitPredictionInput.serializer(), request),
                timeoutSeconds = timeoutSeconds,
            )
            if (result.exitCode != 0) {
                error("profit prediction inference failed exit=${result.exitCode} stderr=${result.stderr}")
            }
            return@withContext json.decodeFromString(ProfitPredictionOutput.serializer(), result.stdout)
        }

        ensureServiceRunning()

        val requestBody = json.encodeToString(ProfitPredictionInput.serializer(), request)
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$serviceUrl/predict"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, Charsets.UTF_8))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build()

        val response = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: Exception) {
            error("Failed to send inference request to Python service: ${e.message}")
        }

        if (response.statusCode() != 200) {
            error("Python inference service returned status ${response.statusCode()}: ${response.body()}")
        }

        try {
            json.decodeFromString(ProfitPredictionOutput.serializer(), response.body())
        } catch (e: Exception) {
            error("Failed to decode prediction output JSON: ${e.message}; body=${response.body().take(4000)}")
        }
    }
}

interface ProfitPredictionTargetSelector {
    suspend fun generateTargets(
        tradeDate: LocalDate,
        targetDate: LocalDate,
        universeSymbols: List<String>,
        sentiment: MarketSentimentSnapshot,
    ): List<TargetPosition>

    suspend fun generateIntradayTargets(
        tradeDate: LocalDate,
        universeSymbols: List<String>,
        realtimeDailyCandles: Map<String, Candle>,
        sentiment: MarketSentimentSnapshot,
    ): List<TargetPosition>
}

@Serializable
internal data class ProfitPredictionInput(
    val tradeDate: String,
    val universe: List<String>,
    val stocks: List<Stock>,
) {
    @Serializable
    data class Stock(
        val tsCode: String,
        val rows: List<Row>,
    )

    @Serializable
    data class Row(
        val tradeDate: String,
        val openQfq: Double,
        val highQfq: Double,
        val lowQfq: Double,
        val closeQfq: Double,
        val volumeQfq: Double,
        val turnoverReal: Double,
    )
}

@Serializable
internal data class ProfitPredictionOutput(
    val modelId: String,
    val topic: String,
    val tradeDate: String,
    val thresholdName: String,
    val threshold: Double,
    val topN: Int,
    val predictions: List<Prediction>,
    val skipped: List<Skipped> = emptyList(),
    val coverage: Coverage,
) {
    @Serializable
    data class Prediction(
        val tsCode: String,
        val score: Double,
        val selectedByThreshold: Boolean,
        val selectedByTopN: Boolean,
    )

    @Serializable
    data class Skipped(
        val tsCode: String,
        val reason: String,
    )

    @Serializable
    data class Coverage(
        val requested: Int,
        val scored: Int,
        val skipped: Int,
    )
}

internal enum class CandidateMode(val propertyValue: String) {
    MARKET_GATED("market-gated"),
    ALL_UNIVERSE("all-universe");

    companion object {
        fun fromProperty(value: String): CandidateMode =
            entries.firstOrNull { it.propertyValue == value.lowercase(Locale.ROOT) }
                ?: error("Unsupported quant.profitPrediction.candidateMode=$value")
    }
}

internal data class DailyDataReadiness(
    val stockDailyUpdated: Boolean,
    val stockDailyFqUpdated: Boolean,
)

internal interface ProfitPredictionDataSource {
    fun loadDailyReadiness(tradeDate: LocalDate): DailyDataReadiness
    fun findPreviousTradingDate(tradeDate: LocalDate): LocalDate?

    fun loadCandidateSymbols(
        tradeDate: LocalDate,
        universeSymbols: List<String>,
        mode: CandidateMode,
        marketGatedSampleSize: Int,
        requireTopListCandidates: Boolean,
    ): List<String>

    fun loadRecentOhlcvWindows(
        tsCodes: List<String>,
        endDateInclusive: LocalDate,
        limitPerStock: Int,
    ): Map<String, List<ProductionOhlcvWindowRow>>
}

internal class DatabaseProfitPredictionDataSource : ProfitPredictionDataSource {
    override fun loadDailyReadiness(tradeDate: LocalDate): DailyDataReadiness =
        DailyDataReadiness(
            stockDailyUpdated = TradingCalendarRepository.isStockDailyUpdated(tradeDate),
            stockDailyFqUpdated = TradingCalendarRepository.isStockDailyFqUpdated(tradeDate),
        )

    override fun findPreviousTradingDate(tradeDate: LocalDate): LocalDate? =
        TradingCalendarRepository.findPreviousTradingDate(tradeDate)

    override fun loadCandidateSymbols(
        tradeDate: LocalDate,
        universeSymbols: List<String>,
        mode: CandidateMode,
        marketGatedSampleSize: Int,
        requireTopListCandidates: Boolean,
    ): List<String> {
        val universe = universeSymbols.distinct().sorted()
        if (mode == CandidateMode.ALL_UNIVERSE) return universe

        val universeSet = universe.toSet()
        val topListCodes = TopListRepository.findCodesByTradeDate(tradeDate)
            .asSequence()
            .filter { it in universeSet }
            .distinct()
            .sorted()
            .toList()
        if (requireTopListCandidates && topListCodes.isEmpty()) {
            error("profit prediction market-gated candidate pool has no top-list candidates tradeDate=$tradeDate")
        }
        val listedSet = topListCodes.toSet()
        val sampledUnlisted = universe.asSequence()
            .filterNot { it in listedSet }
            .sortedWith(compareBy<String> { deterministicCandidateRank(tradeDate, it) }.thenBy { it })
            .take(marketGatedSampleSize.coerceAtLeast(0))
            .toList()
            .sorted()
        return (topListCodes + sampledUnlisted).distinct()
    }

    override fun loadRecentOhlcvWindows(
        tsCodes: List<String>,
        endDateInclusive: LocalDate,
        limitPerStock: Int,
    ): Map<String, List<ProductionOhlcvWindowRow>> =
        StockDailyCandleRepository.findRecentOhlcvWindowsForProduction(tsCodes, endDateInclusive, limitPerStock)

    private fun deterministicCandidateRank(tradeDate: LocalDate, tsCode: String): Int {
        val hash = "$tradeDate:$tsCode".hashCode()
        return if (hash == Int.MIN_VALUE) Int.MAX_VALUE else abs(hash)
    }
}

private fun Candle.toIntradayOhlcvWindowRow(tradeDate: LocalDate): ProductionOhlcvWindowRow? {
    if (date != tradeDate) return null
    val openValue = positiveQfqOrRaw(openQfq, open) ?: return null
    val highValue = positiveQfqOrRaw(highQfq, high) ?: return null
    val lowValue = positiveQfqOrRaw(lowQfq, low) ?: return null
    val closeValue = positiveQfqOrRaw(closeQfq, close) ?: return null
    val volumeValue = positiveQfqOrRaw(volumeQfq, volume) ?: return null
    return ProductionOhlcvWindowRow(
        tsCode = tsCode,
        tradeDate = tradeDate,
        openQfq = openValue,
        highQfq = highValue,
        lowQfq = lowValue,
        closeQfq = closeValue,
        volumeQfq = volumeValue,
        turnoverReal = turnoverReal.coerceAtLeast(0f).toDouble(),
        adjustedComplete = true,
    )
}

private fun positiveQfqOrRaw(qfq: Float, raw: Float): Double? =
    when {
        qfq > 0f -> qfq.toDouble()
        raw > 0f -> raw.toDouble()
        else -> null
    }

internal data class ProcessExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

internal interface ProfitPredictionProcessRunner {
    suspend fun run(
        command: List<String>,
        workingDir: File,
        stdin: String,
        timeoutSeconds: Long,
    ): ProcessExecutionResult
}

internal class DefaultProfitPredictionProcessRunner : ProfitPredictionProcessRunner {
    override suspend fun run(
        command: List<String>,
        workingDir: File,
        stdin: String,
        timeoutSeconds: Long,
    ): ProcessExecutionResult = coroutineScope {
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .start()
        val stdout = async(Dispatchers.IO) {
            process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        }
        val stderr = async(Dispatchers.IO) {
            process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        }
        withContext(Dispatchers.IO) {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(stdin)
            }
        }
        val finished = withContext(Dispatchers.IO) {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        }
        if (!finished) {
            process.destroyForcibly()
        }
        ProcessExecutionResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdout.await(),
            stderr = stderr.await(),
            timedOut = !finished,
        )
    }
}

private fun resolveConfiguredPath(path: String, projectRoot: File): File {
    val file = File(path)
    return if (file.isAbsolute) file.canonicalFile else File(projectRoot, path).canonicalFile
}

private fun resolveProjectRoot(configured: String?): File {
    configured?.takeIf { it.isNotBlank() }?.let { return File(it).canonicalFile }
    generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }.forEach { dir ->
        if (File(dir, "settings.gradle.kts").isFile && File(dir, "strategy-server").isDirectory) {
            return dir
        }
    }
    return File(System.getProperty("user.dir")).canonicalFile
}
