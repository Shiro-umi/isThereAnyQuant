package org.shiroumi.strategy.core.daily.seed

import model.dataprovider.SentimentRuntimeSeed
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.sentiment.SentimentRuntimeMath

object SentimentRuntimeSeedBuilder {
    fun buildBullRatioWindow(
        latestBullRatio: Double,
        fullHistory: List<MarketSentimentSnapshot>,
        previousSeed: SentimentRuntimeSeed?
    ): List<Double> {
        previousSeed?.bullRatioHistory?.takeIf { it.isNotEmpty() }?.let { previousWindow ->
            return SentimentRuntimeMath.appendRollingWindow(previousWindow, latestBullRatio)
        }
        return fullHistory
            .map { it.bullRatio }
            .takeLast(SentimentRuntimeMath.RUNTIME_WINDOW)
    }

    fun buildMarketVolWindow(
        latestMarketVol: Double,
        fullHistory: List<MarketSentimentSnapshot>,
        previousSeed: SentimentRuntimeSeed?
    ): List<Double> {
        previousSeed?.marketVolHistory?.takeIf { it.isNotEmpty() }?.let { previousWindow ->
            return SentimentRuntimeMath.appendRollingWindow(previousWindow, latestMarketVol)
        }
        return fullHistory
            .map { it.marketVol }
            .takeLast(SentimentRuntimeMath.RUNTIME_WINDOW)
    }

    fun buildAccelWindow(
        latestBullRatio: Double,
        fullHistory: List<MarketSentimentSnapshot>,
        previousSeed: SentimentRuntimeSeed?
    ): List<Double> {
        previousSeed?.let { seed ->
            return SentimentRuntimeMath.appendAccelWindow(
                previousBullRatio = seed.bullRatioHistory.lastOrNull(),
                currentBullRatio = latestBullRatio,
                previousAccelWindow = seed.accelHistory
            )
        }
        return SentimentRuntimeMath.rebuildAccelWindowFromBullRatioHistory(
            fullHistory.map { it.bullRatio }
        )
    }
}
