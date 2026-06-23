package org.shiroumi.strategy.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.ProductionOhlcvWindowRow
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.service.model.CandidateMode
import org.shiroumi.strategy.service.model.DailyDataReadiness
import org.shiroumi.strategy.service.model.DatabaseProfitPredictionDataSource
import org.shiroumi.strategy.service.model.ProfitPredictionDataSource
import org.shiroumi.strategy.service.model.ProfitPredictionModelSelector
import org.shiroumi.strategy.service.universe.MainBoardUniverseProvider
import utils.logger

private val logger by logger("BackfillProfitPredictionSelections")

fun main() = runBlocking {
    Class.forName("com.mysql.cj.jdbc.Driver")
    ConfigManager.load()

    val start = LocalDate.parse(System.getProperty("quant.profitPrediction.backfill.start", "2010-01-01"))
    val end = LocalDate.parse(System.getProperty("quant.profitPrediction.backfill.end", "2026-06-08"))
    val failFast = System.getProperty("quant.profitPrediction.backfill.failFast", "false").toBoolean()
    val overwrite = System.getProperty("quant.profitPrediction.backfill.overwrite", "true").toBoolean()
    val chunkSize = System.getProperty("quant.profitPrediction.backfill.chunkSize", "120").toInt().coerceAtLeast(1)
    val seqLen = System.getProperty("quant.profitPrediction.seqLen", "20").toInt().coerceAtLeast(1)
    // 与 ProfitPredictionModelSelector 的取窗一致: seqLen + 特征上下文(最长滚动窗 60 日)
    val featureContextDays = System.getProperty("quant.profitPrediction.featureContextDays", "60").toInt().coerceAtLeast(0)
    val warmupStart = LocalDate.parse(System.getProperty("quant.profitPrediction.backfill.warmupStart", "2009-01-01"))

    val allOpenDates = TradingCalendarRepository.findOpenDates(warmupStart, end)
    val tradeDates = allOpenDates.filter { it >= start && it <= end }
    val universeSymbols = MainBoardUniverseProvider.getActiveSymbols()
    var success = 0
    val failures = mutableListOf<String>()

    logger.info(
        "[盈利预测回填] start=$start end=$end dates=${tradeDates.size} universe=${universeSymbols.size} " +
            "overwrite=$overwrite failFast=$failFast"
    )

    tradeDates.chunked(chunkSize).forEachIndexed { chunkIndex, chunkDates ->
        val firstIndex = allOpenDates.indexOf(chunkDates.first())
        val warmupIndex = (firstIndex - seqLen - featureContextDays - 5).coerceAtLeast(0)
        val preloadStart = allOpenDates[warmupIndex]
        val preloadEnd = chunkDates.last()
        val ohlcvRows = StockDailyCandleRepository.findProductionOhlcvRange(
            tsCodes = universeSymbols,
            startDate = preloadStart,
            endDate = preloadEnd,
        )
        val dataSource = BackfillProfitPredictionDataSource(
            rows = ohlcvRows,
        )
        val selector = ProfitPredictionModelSelector(dataSource = dataSource)
        logger.info(
            "[盈利预测回填] chunk=${chunkIndex + 1}/${(tradeDates.size + chunkSize - 1) / chunkSize} " +
                "dates=${chunkDates.first()}..${chunkDates.last()} preload=$preloadStart..$preloadEnd rows=${ohlcvRows.size}"
        )

        chunkDates.forEachIndexed { offset, tradeDate ->
            val globalIndex = chunkIndex * chunkSize + offset
            val existingRows = DailyProfitPredictionSelectionRepository.countByDate(tradeDate)
            if (!overwrite && existingRows > 0) {
                logger.info("[盈利预测回填] skip tradeDate=$tradeDate existingRows=$existingRows")
                return@forEachIndexed
            }

            val targetDate = TradingCalendarRepository.findNextTradingDate(tradeDate) ?: tradeDate
            runCatching {
                val targets = selector.generateTargets(
                    tradeDate = tradeDate,
                    targetDate = targetDate,
                    universeSymbols = universeSymbols,
                    sentiment = neutralSentiment(tradeDate),
                )
                DailyProfitPredictionSelectionRepository.deleteByDate(tradeDate)
                DailyProfitPredictionSelectionRepository.replaceForDate(
                    tradeDate = tradeDate,
                    positions = targets,
                )
                success += 1
                logger.info(
                    "[盈利预测回填] ${globalIndex + 1}/${tradeDates.size} tradeDate=$tradeDate targetDate=$targetDate " +
                        "rows=${targets.size} selected=${targets.count { it.selected && it.targetWeight > 0.0 }}"
                )
            }.onFailure { error ->
                val message = "tradeDate=$tradeDate error=${error.message}"
                failures += message
                logger.error("[盈利预测回填] failed $message")
                if (failFast) throw error
            }
        }
    }

    logger.info("[盈利预测回填] finished success=$success failed=${failures.size}")
    if (failures.isNotEmpty()) {
        println("[profit-prediction-backfill] failures=${failures.size}")
        failures.take(20).forEach { println("[profit-prediction-backfill] $it") }
    }
    println("[profit-prediction-backfill] success=$success failed=${failures.size}")
}

private fun neutralSentiment(tradeDate: LocalDate) = MarketSentimentSnapshot(
    tradeDate = tradeDate,
    signalBasis = "BACKFILL_MODEL_ONLY",
    sampleSize = 0,
    bullRatio = 0.0,
    fftScore = 0.0,
    residualScore = 0.0,
    marketVol = 0.0,
    volZ = 0.0,
    accelZ = 0.0,
    sentimentExposure = 1.0,
    ratioNorm = 0.0,
    volScore = 0.0,
    accelScore = 0.0,
    absoluteFloor = 1.0,
    volCap = 1.0,
    sufficientHistory = true,
    requiredHistory = 0,
    reason = "model-only historical backfill",
)

private class BackfillProfitPredictionDataSource(
    rows: List<ProductionOhlcvWindowRow>,
    private val fallback: DatabaseProfitPredictionDataSource = DatabaseProfitPredictionDataSource(),
) : ProfitPredictionDataSource {
    private val rowsByCode: Map<String, List<ProductionOhlcvWindowRow>> =
        rows.groupBy { it.tsCode }.mapValues { (_, values) -> values.sortedBy { it.tradeDate } }

    override fun loadDailyReadiness(tradeDate: LocalDate): DailyDataReadiness =
        DailyDataReadiness(stockDailyUpdated = true, stockDailyFqUpdated = true)

    override fun loadCandidateSymbols(
        tradeDate: LocalDate,
        universeSymbols: List<String>,
        mode: CandidateMode,
        marketGatedSampleSize: Int,
        requireTopListCandidates: Boolean,
    ): List<String> {
        if (mode == CandidateMode.ALL_UNIVERSE) return universeSymbols.distinct().sorted()
        return fallback.loadCandidateSymbols(
            tradeDate = tradeDate,
            universeSymbols = universeSymbols,
            mode = mode,
            marketGatedSampleSize = marketGatedSampleSize,
            requireTopListCandidates = requireTopListCandidates,
        )
    }

    override fun loadRecentOhlcvWindows(
        tsCodes: List<String>,
        endDateInclusive: LocalDate,
        limitPerStock: Int,
    ): Map<String, List<ProductionOhlcvWindowRow>> {
        val result = linkedMapOf<String, List<ProductionOhlcvWindowRow>>()
        tsCodes.distinct().forEach { tsCode ->
            val rows = rowsByCode[tsCode].orEmpty()
            val endExclusive = rows.upperBound(endDateInclusive)
            if (endExclusive > 0) {
                result[tsCode] = rows.subList((endExclusive - limitPerStock).coerceAtLeast(0), endExclusive)
            }
        }
        return result
    }

    private fun List<ProductionOhlcvWindowRow>.upperBound(date: LocalDate): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (this[mid].tradeDate <= date) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }
}
