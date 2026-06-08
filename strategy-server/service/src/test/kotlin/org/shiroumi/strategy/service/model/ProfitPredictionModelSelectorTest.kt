package org.shiroumi.strategy.service.model

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import model.Candle
import org.shiroumi.database.stock.ProductionOhlcvWindowRow
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class ProfitPredictionModelSelectorTest {
    private val tradeDate = LocalDate(2026, 4, 30)
    private val targetDate = LocalDate(2026, 5, 6)
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `blocks model inference when daily data is not fully updated`() = runTest {
        val processRunner = FakeProcessRunner()
        val selector = selector(
            dataSource = FakeDataSource(
                readiness = DailyDataReadiness(stockDailyUpdated = true, stockDailyFqUpdated = false),
                candidates = listOf("000001.SZ", "000002.SZ"),
            ),
            processRunner = processRunner,
        )

        val error = assertFailsWith<IllegalStateException> {
            selector.generateTargets(
                tradeDate = tradeDate,
                targetDate = targetDate,
                universeSymbols = listOf("000001.SZ", "000002.SZ"),
                sentiment = sentiment(),
            )
        }

        assertTrue(error.message.orEmpty().contains("incomplete daily data"))
        assertFalse(processRunner.wasCalled)
    }

    @Test
    fun `uses candidate pool and persists full ranked daily model predictions`() = runTest {
        val candidates = listOf("000001.SZ", "000002.SZ", "000003.SZ")
        val processRunner = FakeProcessRunner(
            stdout = """
                {
                  "modelId": "unit-model",
                  "topic": "profit_prediction_7pct",
                  "tradeDate": "2026-04-30",
                  "thresholdName": "recall_0_2",
                  "threshold": 0.5,
                  "topN": 2,
                  "predictions": [
                    {"tsCode": "000002.SZ", "score": 0.91, "selectedByThreshold": true, "selectedByTopN": true},
                    {"tsCode": "000001.SZ", "score": 0.93, "selectedByThreshold": true, "selectedByTopN": true},
                    {"tsCode": "000003.SZ", "score": 0.20, "selectedByThreshold": false, "selectedByTopN": false}
                  ],
                  "skipped": [],
                  "coverage": {"requested": 3, "scored": 3, "skipped": 0}
                }
            """.trimIndent()
        )
        val dataSource = FakeDataSource(
            candidates = candidates,
            windows = candidates.associateWith { completeWindow(it) },
        )
        val selector = selector(dataSource = dataSource, processRunner = processRunner, topN = 2)

        val targets = selector.generateTargets(
            tradeDate = tradeDate,
            targetDate = targetDate,
            universeSymbols = listOf("000001.SZ", "000002.SZ", "000003.SZ", "600000.SH"),
            sentiment = sentiment(exposure = 0.6),
        )

        assertEquals(candidates, dataSource.lastRequestedWindows)
        assertEquals(listOf("000001.SZ", "000002.SZ", "000003.SZ"), targets.map { it.tsCode })
        assertEquals(listOf(0.93, 0.91, 0.20), targets.map { it.selectionScore })
        assertEquals(listOf(true, true, false), targets.map { it.selected })
        assertEquals(listOf(0.5, 0.5, 0.0), targets.map { it.targetWeight })
        val input = json.decodeFromString(ProfitPredictionInput.serializer(), processRunner.stdin)
        assertEquals(candidates, input.universe)
        assertEquals(candidates, input.stocks.map { it.tsCode })
    }

    @Test
    fun `keeps model topN selected when sentiment exposure is zero`() = runTest {
        val candidates = listOf("000001.SZ", "000002.SZ")
        val processRunner = FakeProcessRunner(
            stdout = """
                {
                  "modelId": "unit-model",
                  "topic": "profit_prediction_7pct",
                  "tradeDate": "2026-04-30",
                  "thresholdName": "recall_0_2",
                  "threshold": 0.5,
                  "topN": 2,
                  "predictions": [
                    {"tsCode": "000001.SZ", "score": 0.93, "selectedByThreshold": true, "selectedByTopN": true},
                    {"tsCode": "000002.SZ", "score": 0.91, "selectedByThreshold": true, "selectedByTopN": true}
                  ],
                  "skipped": [],
                  "coverage": {"requested": 2, "scored": 2, "skipped": 0}
                }
            """.trimIndent()
        )
        val selector = selector(
            dataSource = FakeDataSource(
                candidates = candidates,
                windows = candidates.associateWith { completeWindow(it) },
            ),
            processRunner = processRunner,
            topN = 2,
        )

        val targets = selector.generateTargets(
            tradeDate = tradeDate,
            targetDate = targetDate,
            universeSymbols = candidates,
            sentiment = sentiment(exposure = 0.0),
        )

        assertEquals(listOf(true, true), targets.map { it.selected })
        assertEquals(listOf(0.5, 0.5), targets.map { it.targetWeight })
        assertTrue(targets.all { !it.selectionReason.orEmpty().contains("情绪") })
    }

    @Test
    fun `uses all universe as default daily candidate mode`() = runTest {
        val candidates = listOf("000001.SZ", "000002.SZ")
        val dataSource = FakeDataSource(
            candidates = candidates,
            windows = candidates.associateWith { completeWindow(it) },
        )
        val selector = selector(dataSource = dataSource, processRunner = FakeProcessRunner())

        selector.generateTargets(
            tradeDate = tradeDate,
            targetDate = targetDate,
            universeSymbols = candidates,
            sentiment = sentiment(),
        )

        assertEquals(CandidateMode.ALL_UNIVERSE, dataSource.lastCandidateMode)
        assertFalse(dataSource.lastRequireTopListCandidates)
    }

    @Test
    fun `blocks inference when model input coverage is too low`() = runTest {
        val candidates = listOf("000001.SZ", "000002.SZ", "000003.SZ")
        val selector = selector(
            dataSource = FakeDataSource(
                candidates = candidates,
                windows = mapOf("000001.SZ" to completeWindow("000001.SZ")),
            ),
            processRunner = FakeProcessRunner(),
            topN = 2,
            minCoverageRatio = 0.85,
        )

        val error = assertFailsWith<IllegalStateException> {
            selector.generateTargets(
                tradeDate = tradeDate,
                targetDate = targetDate,
                universeSymbols = candidates,
                sentiment = sentiment(),
            )
        }

        assertTrue(error.message.orEmpty().contains("insufficient model input coverage"))
    }

    @Test
    fun `intraday targets append realtime day candle to historical window`() = runTest {
        val candidates = listOf("000001.SZ", "000002.SZ")
        val processRunner = FakeProcessRunner(
            stdout = """
                {
                  "modelId": "unit-model",
                  "topic": "profit_prediction_7pct",
                  "tradeDate": "2026-04-30",
                  "thresholdName": "recall_0_2",
                  "threshold": 0.5,
                  "topN": 1,
                  "predictions": [
                    {"tsCode": "000002.SZ", "score": 0.88, "selectedByThreshold": true, "selectedByTopN": true},
                    {"tsCode": "000001.SZ", "score": 0.81, "selectedByThreshold": true, "selectedByTopN": false}
                  ],
                  "skipped": [],
                  "coverage": {"requested": 2, "scored": 2, "skipped": 0}
                }
            """.trimIndent()
        )
        val selector = selector(
            dataSource = FakeDataSource(
                candidates = candidates,
                windows = candidates.associateWith { historicalWindow19(it) },
            ),
            processRunner = processRunner,
            topN = 1,
        )

        val targets = selector.generateIntradayTargets(
            tradeDate = tradeDate,
            universeSymbols = candidates,
            realtimeDailyCandles = candidates.associateWith { candle(it, close = 11f, turnoverReal = 0f) },
            sentiment = sentiment(exposure = 1.0),
        )

        assertEquals(listOf("000002.SZ"), targets.map { it.tsCode })
        val input = json.decodeFromString(ProfitPredictionInput.serializer(), processRunner.stdin)
        assertEquals(20, input.stocks.first().rows.size)
        assertEquals("2026-04-30", input.stocks.first().rows.last().tradeDate)
        assertEquals(0.0, input.stocks.first().rows.last().turnoverReal)
    }

    @Test
    fun `default process runner drains stderr while waiting for process`() = runTest {
        val workDir = Files.createTempDirectory("profit-process-runner").toFile()
        val command = listOf(
            "python3",
            "-c",
            """
                import sys
                sys.stdin.read()
                sys.stderr.write("x" * 200000)
                sys.stderr.flush()
                print('{"ok": true}')
            """.trimIndent()
        )

        val result = DefaultProfitPredictionProcessRunner().run(
            command = command,
            workingDir = workDir,
            stdin = """{"request": true}""",
            timeoutSeconds = 10,
        )

        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertEquals("""{"ok": true}""", result.stdout.trim())
        assertEquals(200000, result.stderr.length)
    }

    private fun selector(
        dataSource: ProfitPredictionDataSource,
        processRunner: ProfitPredictionProcessRunner,
        topN: Int = 2,
        minCoverageRatio: Double = 0.0,
    ): ProfitPredictionModelSelector {
        val root = Files.createTempDirectory("profit-selector-root").toFile()
        File(root, "model").mkdirs()
        File(root, "python").mkdirs()
        return ProfitPredictionModelSelector(
            projectRoot = root,
            modelDir = "model",
            pythonWorkDir = "python",
            command = listOf("unused"),
            timeoutSeconds = 5,
            topN = topN,
            seqLen = 20,
            minCoverageRatio = minCoverageRatio,
            requireTopListCandidates = false,
            dataSource = dataSource,
            processRunner = processRunner,
        )
    }

    private fun completeWindow(tsCode: String): List<ProductionOhlcvWindowRow> =
        (19 downTo 0).map { offset ->
            val date = LocalDate(2026, 4, 30 - offset)
            ProductionOhlcvWindowRow(
                tsCode = tsCode,
                tradeDate = date,
                openQfq = 10.0 + offset,
                highQfq = 11.0 + offset,
                lowQfq = 9.0 + offset,
                closeQfq = 10.5 + offset,
                volumeQfq = 1000.0 + offset,
                turnoverReal = 100_000.0 + offset,
                adjustedComplete = true,
            )
        }

    private fun historicalWindow19(tsCode: String): List<ProductionOhlcvWindowRow> =
        (18 downTo 0).map { offset ->
            val date = LocalDate(2026, 4, 29 - offset)
            ProductionOhlcvWindowRow(
                tsCode = tsCode,
                tradeDate = date,
                openQfq = 10.0 + offset,
                highQfq = 11.0 + offset,
                lowQfq = 9.0 + offset,
                closeQfq = 10.5 + offset,
                volumeQfq = 1000.0 + offset,
                turnoverReal = 100_000.0 + offset,
                adjustedComplete = true,
            )
        }

    private fun candle(
        tsCode: String,
        close: Float,
        turnoverReal: Float,
    ) = Candle(
        tsCode = tsCode,
        date = tradeDate,
        open = 10f,
        high = 13f,
        low = 9f,
        close = close,
        adj = 1f,
        volume = 1000f,
        turnoverReal = turnoverReal,
        pe = 0f,
        peTtm = 0f,
        pb = 0f,
        ps = 0f,
        psTtm = 0f,
        mvTotal = 0f,
        mvCirc = 0f,
    )

    private fun sentiment(exposure: Double = 1.0) = MarketSentimentSnapshot(
        tradeDate = tradeDate,
        signalBasis = "HFQ",
        sampleSize = 3,
        bullRatio = 0.6,
        fftScore = 0.5,
        residualScore = 0.5,
        marketVol = 0.02,
        volZ = 0.0,
        accelZ = 0.0,
        sentimentExposure = exposure,
        ratioNorm = 0.5,
        volScore = 0.5,
        accelScore = 0.5,
        absoluteFloor = 0.256,
        volCap = 1.0,
        sufficientHistory = true,
        requiredHistory = 252,
        reason = "test"
    )
}

private class FakeDataSource(
    private val readiness: DailyDataReadiness = DailyDataReadiness(true, true),
    private val candidates: List<String>,
    private val windows: Map<String, List<ProductionOhlcvWindowRow>> = candidates.associateWith { emptyList() },
) : ProfitPredictionDataSource {
    var lastRequestedWindows: List<String> = emptyList()
        private set
    var lastCandidateMode: CandidateMode? = null
        private set
    var lastRequireTopListCandidates: Boolean = true
        private set

    override fun loadDailyReadiness(tradeDate: LocalDate): DailyDataReadiness = readiness

    override fun findPreviousTradingDate(tradeDate: LocalDate): LocalDate? = LocalDate(2026, 4, 29)

    override fun loadCandidateSymbols(
        tradeDate: LocalDate,
        universeSymbols: List<String>,
        mode: CandidateMode,
        marketGatedSampleSize: Int,
        requireTopListCandidates: Boolean,
    ): List<String> {
        lastCandidateMode = mode
        lastRequireTopListCandidates = requireTopListCandidates
        return candidates
    }

    override fun loadRecentOhlcvWindows(
        tsCodes: List<String>,
        endDateInclusive: LocalDate,
        limitPerStock: Int,
    ): Map<String, List<ProductionOhlcvWindowRow>> {
        lastRequestedWindows = tsCodes
        return windows
    }
}

private class FakeProcessRunner(
    private val stdout: String = """
        {
          "modelId": "unit-model",
          "topic": "profit_prediction_7pct",
          "tradeDate": "2026-04-30",
          "thresholdName": "recall_0_2",
          "threshold": 0.5,
          "topN": 2,
          "predictions": [],
          "skipped": [],
          "coverage": {"requested": 0, "scored": 0, "skipped": 0}
        }
    """.trimIndent(),
) : ProfitPredictionProcessRunner {
    var wasCalled: Boolean = false
        private set
    var stdin: String = ""
        private set

    override suspend fun run(
        command: List<String>,
        workingDir: File,
        stdin: String,
        timeoutSeconds: Long,
    ): ProcessExecutionResult {
        wasCalled = true
        this.stdin = stdin
        return ProcessExecutionResult(
            exitCode = 0,
            stdout = stdout,
            stderr = "",
            timedOut = false,
        )
    }
}
