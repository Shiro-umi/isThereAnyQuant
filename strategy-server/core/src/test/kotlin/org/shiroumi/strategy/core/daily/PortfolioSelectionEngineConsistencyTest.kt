package org.shiroumi.strategy.core.daily

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.shiroumi.strategy.core.intraday.IntradayPortfolioGenerator

/**
 * 盘后/盘中选股一致性测试。
 *
 * 验证 [TargetPortfolioGenerator] 和 [IntradayPortfolioGenerator] 对同一输入
 * 产生的选股结果和 selectionScore 排序一致。
 *
 * 关键约束（来自 design doc §6）：
 * - `rankScore` 是单股因子分（由 StockFactorCalculator 计算）
 * - `selectionScore` 是最终横截面选股分（由 PortfolioSelectionEngine 计算）
 * - 盘后和盘中必须复用同一套组合选择引擎
 */
class PortfolioSelectionEngineConsistencyTest {

    private val tradeDate = LocalDate(2026, 4, 30)
    private val targetDate = LocalDate(2026, 5, 6)

    @Test
    @DisplayName("同一输入下盘后和盘中选出相同的股票代码和顺序")
    fun `daily and intraday select same stocks in same order`() {
        val sentiment = buildSentiment(exposure = 0.6)
        val factors = buildFactors(10)

        val dailyPositions = TargetPortfolioGenerator.generate(tradeDate, targetDate, factors, sentiment)
            .filter { it.selected }
            .map { it.tsCode }

        val intradayPositions = IntradayPortfolioGenerator.generate(
            tradeDate = tradeDate,
            timestamp = 0L,
            factors = factors,
            sentiment = sentiment
        ).filter { it.selected }.map { it.tsCode }

        assertEquals(dailyPositions, intradayPositions, "盘后和盘中选股结果必须一致")
        assertTrue(dailyPositions.isNotEmpty(), "情绪为0.6时应选出股票")
    }

    @Test
    @DisplayName("selectionScore 排序：高分在前，低分在后")
    fun `selectionScore sorts high to low`() {
        val sentiment = buildSentiment(exposure = 0.8)
        val factors = buildFactors(10)

        val selections = PortfolioSelectionEngine.selectTopSelections(
            factors = factors,
            sentimentExposure = sentiment.sentimentExposure,
            topN = 5
        )

        assertTrue(selections.size <= 5, "最多选5只")
        if (selections.size >= 2) {
            for (i in 0 until selections.size - 1) {
                assertTrue(
                    selections[i].selectionScore >= selections[i + 1].selectionScore,
                    "selectionScore 应降序排列: idx=$i ${selections[i].selectionScore} < ${selections[i + 1].selectionScore}"
                )
            }
        }
    }

    @Test
    @DisplayName("无信号股票不应被选中")
    fun `noSignal stocks are not selected`() {
        val sentiment = buildSentiment(exposure = 0.8)
        val factors = buildFactors(10) + factor(
            tsCode = "999999.SH",
            signal = false,
            rankScore = 99.0 // rankScore 很高但 signal=false
        )

        val selectedCodes = PortfolioSelectionEngine.selectTopSelections(
            factors = factors,
            sentimentExposure = sentiment.sentimentExposure,
            topN = 5
        ).map { it.factor.tsCode }

        assertTrue("999999.SH" !in selectedCodes, "signal=false 的股票不应被选中，即使 rankScore 很高")
    }

    @Test
    @DisplayName("情绪为0时盘后和盘中都不选股")
    fun `zero exposure produces no selections`() {
        val sentiment = buildSentiment(exposure = 0.0)
        val factors = buildFactors(10)

        val daily = TargetPortfolioGenerator.generate(tradeDate, targetDate, factors, sentiment)
        val intraday = IntradayPortfolioGenerator.generate(
            tradeDate = tradeDate,
            timestamp = 0L,
            factors = factors,
            sentiment = sentiment
        )

        assertTrue(daily.none { it.selected }, "情绪为0时盘后不应选股")
        assertTrue(intraday.none { it.selected }, "情绪为0时盘中不应选股")
    }

    @Test
    @DisplayName("IntradayPortfolioGenerator 的 rankScore 等于 selectionScore")
    fun `intraday rankScore equals selectionScore`() {
        val sentiment = buildSentiment(exposure = 0.7)
        val factors = buildFactors(8)

        val intradayPositions = IntradayPortfolioGenerator.generate(
            tradeDate = tradeDate,
            timestamp = 0L,
            factors = factors,
            sentiment = sentiment
        )

        val selections = PortfolioSelectionEngine.selectTopSelections(
            factors = factors,
            sentimentExposure = sentiment.sentimentExposure,
            topN = 5
        ).associate { it.factor.tsCode to it.selectionScore }

        intradayPositions.filter { it.selected }.forEach { pos ->
            val expectedScore = selections[pos.tsCode]
            assertTrue(expectedScore != null, "${pos.tsCode} 应有对应的 selectionScore")
            assertEquals(
                expectedScore!!, pos.rankScore,
                1e-9,
                "盘中 rankScore 应等于 selectionScore for ${pos.tsCode}"
            )
        }
    }

    private fun buildSentiment(exposure: Double): MarketSentimentSnapshot = MarketSentimentSnapshot(
        tradeDate = tradeDate,
        signalBasis = "HFQ",
        sampleSize = 500,
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
        requiredHistory = 400,
        reason = null,
    )

    private fun buildFactors(count: Int): List<StockFactorSnapshot> =
        (1..count).map { idx ->
            factor(
                tsCode = "600${idx.toString().padStart(3, '0')}.SH",
                momentum20 = 0.1 * idx,
                volRatio520 = 1.0 + idx * 0.2,
                amomCombined = 0.5 + idx * 0.1,
                rankScore = 0.05 * idx
            )
        }

    private fun factor(
        tsCode: String,
        signal: Boolean = true,
        momentum20: Double = 0.5,
        volRatio520: Double = 1.5,
        amomCombined: Double = 1.0,
        rankScore: Double = 0.5,
    ): StockFactorSnapshot = StockFactorSnapshot(
        tradeDate = tradeDate,
        tsCode = tsCode,
        signalBasis = "HFQ",
        executionBasis = "RAW",
        sufficientHistory = true,
        requiredHistory = 400,
        open = 10.0,
        high = 11.0,
        low = 9.0,
        close = 10.5 + rankScore,
        volume = 10000.0,
        executionOpen = 10.0,
        executionClose = 10.5,
        hfqFactor = 1.0,
        ema10 = 10.0,
        ema30 = 9.0,
        emaBull = true,
        atr14 = 0.5,
        signal = signal,
        momentum20 = momentum20,
        volRatio520 = volRatio520,
        amomCombined = amomCombined,
        rankScore = rankScore,
    )
}
