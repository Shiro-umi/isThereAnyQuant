package org.shiroumi.strategy.core.daily.seed

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import model.dataprovider.SentimentRuntimeSeed
import model.dataprovider.SentimentScopes
import model.dataprovider.SentimentSymbolStateSeed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.sentiment.SentimentRuntimeMath
import kotlin.math.sin

class SentimentRuntimeSeedBuilderTest {

    @Test
    @DisplayName("seed 窗口重建应保留预热期无效快照的连续性")
    fun keepContinuousWarmupHistoryBeforeFirstValidDay() {
        val tradeDate = LocalDate(2024, 12, 31)
        val history = (0 until 420).map { index ->
            snapshot(
                tradeDate = startDate.plus(DatePeriod(days = index)),
                bullRatio = 0.12 + index * 0.001,
                marketVol = 0.01 + index * 0.0001,
                sufficientHistory = index >= 400,
                reason = if (index >= 400) null else "预热期"
            )
        }

        val warmupHistory = history.take(120)
        val latest = warmupHistory.last()

        val bullRatioWindow = SentimentRuntimeSeedBuilder.buildBullRatioWindow(
            latestBullRatio = latest.bullRatio,
            fullHistory = warmupHistory,
            previousSeed = null
        )
        val marketVolWindow = SentimentRuntimeSeedBuilder.buildMarketVolWindow(
            latestMarketVol = latest.marketVol,
            fullHistory = warmupHistory,
            previousSeed = null
        )
        val accelWindow = SentimentRuntimeSeedBuilder.buildAccelWindow(
            latestBullRatio = latest.bullRatio,
            fullHistory = warmupHistory,
            previousSeed = null
        )

        assertEquals(120, bullRatioWindow.size)
        assertEquals(120, marketVolWindow.size)
        assertEquals(120, accelWindow.size)
        assertEquals(warmupHistory.map { it.bullRatio }, bullRatioWindow)
        assertEquals(warmupHistory.map { it.marketVol }, marketVolWindow)
        assertEquals(
            SentimentRuntimeMath.rebuildAccelWindowFromBullRatioHistory(warmupHistory.map { it.bullRatio }),
            accelWindow
        )

        val fullWindow = SentimentRuntimeSeedBuilder.buildBullRatioWindow(
            latestBullRatio = tradeDateDay(history, tradeDate).bullRatio,
            fullHistory = history,
            previousSeed = null
        )
        assertEquals(SentimentRuntimeMath.RUNTIME_WINDOW, fullWindow.size)
    }

    @Test
    @DisplayName("存在前一日 seed 时应连续追加当天快照，无论当天是否有效")
    fun appendPreviousSeedContinuously() {
        val history = (0 until 30).map { index ->
            snapshot(
                tradeDate = startDate.plus(DatePeriod(days = index)),
                bullRatio = 0.35 + sin(index / 7.0) * 0.05,
                marketVol = 0.02 + index * 0.0002,
                sufficientHistory = false,
                reason = "预热期"
            )
        }
        val previousSeed = SentimentRuntimeSeed(
            scope = SentimentScopes.MAIN_BOARD,
            forTradeDate = startDate.plus(DatePeriod(days = 29)),
            sourceTradeDate = startDate.plus(DatePeriod(days = 28)),
            signalBasis = "HFQ",
            requiredHistory = 400,
            sampleSize = 500,
            sampleCodes = listOf("000001.SZ"),
            symbolStates = listOf(
                SentimentSymbolStateSeed(
                    tsCode = "000001.SZ",
                    emaShort = 1.0,
                    emaLong = 1.0,
                    prevClose = 1.0,
                    recentReturns = List(20) { 0.0 },
                    nextReturnIndex = 0,
                    returnWindowSize = 20,
                    returnSum = 0.0,
                    returnSumSq = 0.0
                )
            ),
            bullRatioHistory = history.dropLast(1).map { it.bullRatio },
            marketVolHistory = history.dropLast(1).map { it.marketVol },
            accelHistory = SentimentRuntimeMath.rebuildAccelWindowFromBullRatioHistory(history.dropLast(1).map { it.bullRatio }),
            combinedHistory = history.dropLast(1).map { 0.0 },
            totalDays = history.size - 1
        )
        val latest = history.last()

        val bullRatioWindow = SentimentRuntimeSeedBuilder.buildBullRatioWindow(
            latestBullRatio = latest.bullRatio,
            fullHistory = history,
            previousSeed = previousSeed
        )
        val marketVolWindow = SentimentRuntimeSeedBuilder.buildMarketVolWindow(
            latestMarketVol = latest.marketVol,
            fullHistory = history,
            previousSeed = previousSeed
        )
        val accelWindow = SentimentRuntimeSeedBuilder.buildAccelWindow(
            latestBullRatio = latest.bullRatio,
            fullHistory = history,
            previousSeed = previousSeed
        )

        assertEquals(history.map { it.bullRatio }, bullRatioWindow)
        assertEquals(history.map { it.marketVol }, marketVolWindow)
        assertEquals(
            SentimentRuntimeMath.appendAccelWindow(
                previousBullRatio = previousSeed.bullRatioHistory.last(),
                currentBullRatio = latest.bullRatio,
                previousAccelWindow = previousSeed.accelHistory
            ),
            accelWindow
        )
    }

    private fun tradeDateDay(history: List<MarketSentimentSnapshot>, tradeDate: LocalDate): MarketSentimentSnapshot {
        return history.first { it.tradeDate == tradeDate }
    }

    private fun snapshot(
        tradeDate: LocalDate,
        bullRatio: Double,
        marketVol: Double,
        sufficientHistory: Boolean,
        reason: String?
    ): MarketSentimentSnapshot {
        return MarketSentimentSnapshot(
            tradeDate = tradeDate,
            signalBasis = "HFQ",
            sampleSize = 500,
            bullRatio = bullRatio,
            fftScore = 0.0,
            residualScore = 0.0,
            marketVol = marketVol,
            volZ = 0.0,
            accelZ = 0.0,
            sentimentExposure = 0.0,
            ratioNorm = 0.0,
            volScore = 0.0,
            accelScore = 0.0,
            absoluteFloor = 0.0,
            volCap = 0.0,
            sufficientHistory = sufficientHistory,
            requiredHistory = 400,
            reason = reason
        )
    }

    private val startDate = LocalDate(2024, 1, 1)
}
