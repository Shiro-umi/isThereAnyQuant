package org.shiroumi.strategy.service.model

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.shiroumi.database.stock.ProductionOhlcvWindowRow
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 纯 EMA20 斜率选股器单测。
 *
 * 覆盖核心正确性：
 *  - EMA20 斜率降序排序 + Top-N 等权
 *  - 信号日收盘涨停票被剔除（不进 selected）
 *  - 防未来函数：窗口末行不是信号日 T 的票被剔除（停牌/数据缺）
 *  - 预热不足（窗口行数 < emaSpan+1）被剔除
 *  - selectionScore = EMA20 斜率（下游 entryPriority 排序依赖）
 *  - sentimentExposure 透传
 */
class Ema20SlopeTargetSelectorTest {

    private val tradeDate = LocalDate(2026, 4, 30)
    private val targetDate = LocalDate(2026, 5, 6)

    /**
     * 造一段长度 [len] 的前复权窗口，末行落在信号日 T。
     * 用每日固定增量 [dailyDelta] 构造单调上行序列：delta 越大 → EMA20 斜率越大。
     * 起始价固定 10.0，保证 close 恒正。
     */
    private fun window(
        tsCode: String,
        dailyDelta: Double,
        len: Int = 60,
        endDate: LocalDate = tradeDate,
        allComplete: Boolean = true,
        startPrice: Double = 10.0,
    ): List<ProductionOhlcvWindowRow> =
        (len - 1 downTo 0).map { offset ->
            // 末行 offset=0 → endDate；往前每行 -1 天，严格递增（斜率不依赖日历间隔，仅用末两点）。
            val date = endDate.minus(offset, DateTimeUnit.DAY)
            val close = startPrice + dailyDelta * (len - 1 - offset)
            ProductionOhlcvWindowRow(
                tsCode = tsCode,
                tradeDate = date,
                openQfq = close,
                highQfq = close + 0.5,
                lowQfq = close - 0.5,
                closeQfq = close,
                volumeQfq = 1000.0,
                turnoverReal = 100_000.0,
                adjustedComplete = allComplete,
            )
        }

    @Test
    fun `斜率降序选 Top-N 等权且 selectionScore 等于斜率`() = runTest {
        // A/B/C/D/E/F 斜率递减（dailyDelta 递减）。topN=3 选 A/B/C。
        val universe = listOf("600001.SH", "600002.SH", "600003.SH", "600004.SH", "600005.SH")
        val windows = mapOf(
            "600001.SH" to window("600001.SH", dailyDelta = 0.50),
            "600002.SH" to window("600002.SH", dailyDelta = 0.40),
            "600003.SH" to window("600003.SH", dailyDelta = 0.30),
            "600004.SH" to window("600004.SH", dailyDelta = 0.20),
            "600005.SH" to window("600005.SH", dailyDelta = 0.10),
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 3,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment(exposure = 0.8))

        // 全量 5 只都打分返回，selected 只有前 3。
        assertEquals(5, targets.size, "全量候选都应返回（含未选中）")
        val selected = targets.filter { it.selected }.map { it.tsCode }
        assertEquals(listOf("600001.SH", "600002.SH", "600003.SH"), selected, "斜率最大的前 3 只被选中")

        // 等权 1/3
        assertTrue(targets.filter { it.selected }.all { kotlin.math.abs(it.targetWeight - 1.0 / 3.0) < 1e-9 }, "selected 等权 1/3")
        assertTrue(targets.filterNot { it.selected }.all { it.targetWeight == 0.0 }, "未选中票权重 0")

        // selectionScore = 斜率，且严格降序（下游 entryPriority 依赖）
        val scores = targets.map { it.selectionScore }
        assertEquals(scores.sortedDescending(), scores, "selectionScore 必须斜率降序")
        assertTrue(scores[0] > scores[1], "斜率大的排前")

        // sentimentExposure 透传
        assertTrue(targets.all { it.sentimentExposure == 0.8 }, "情绪水位透传")
    }

    @Test
    fun `排的是比值斜率而非绝对斜率（高价位大绝对增量票不应胜出）`() = runTest {
        // 反例固化口径：比值斜率 (ema[T]-ema[T-1])/ema[T-1] 同时受绝对增量与分母价位影响。
        // 票H 起始 100、每日 +1.0（绝对增量 ~0.997，是票L 的 3 倍），但高价位分母压制 → 比值斜率仅 ~0.0067。
        // 票L 起始 10、每日 +0.3（绝对增量 ~0.299），低价位 → 比值斜率 ~0.0122。
        // 若误排「绝对斜率」H 会胜出；正确排「比值斜率」L 必须排在 H 前。
        val universe = listOf("600100.SH", "600200.SH")
        val windows = mapOf(
            "600100.SH" to window("600100.SH", dailyDelta = 1.0, startPrice = 100.0), // 票H
            "600200.SH" to window("600200.SH", dailyDelta = 0.3, startPrice = 10.0),   // 票L
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 1,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        val ordered = targets.map { it.tsCode }
        assertEquals(
            listOf("600200.SH", "600100.SH"), ordered,
            "必须按比值斜率降序：低价位但相对斜率更大的票L 排在高价位大绝对增量的票H 之前",
        )
        assertEquals(listOf("600200.SH"), targets.filter { it.selected }.map { it.tsCode }, "Top1 选票L 而非票H")
        // 比值斜率确实压过了绝对增量：票L 的 selectionScore 大于票H
        val scoreL = targets.single { it.tsCode == "600200.SH" }.selectionScore
        val scoreH = targets.single { it.tsCode == "600100.SH" }.selectionScore
        assertTrue(scoreL > scoreH, "票L 比值斜率($scoreL) 必须 > 票H($scoreH)")
    }

    @Test
    fun `信号日收盘涨停票被剔除`() = runTest {
        val universe = listOf("600001.SH", "600002.SH", "600003.SH")
        val windows = mapOf(
            "600001.SH" to window("600001.SH", dailyDelta = 0.50), // 斜率最大，但涨停 → 应被剔除
            "600002.SH" to window("600002.SH", dailyDelta = 0.40),
            "600003.SH" to window("600003.SH", dailyDelta = 0.30),
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 2,
            dataSource = FakeEma20DataSource(
                windows = windows,
                limitUp = setOf("600001.SH"), // 信号日涨停
            ),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        // 涨停的 600001 完全不出现在结果里（连 candidate 都不算，已在打分前剔除）
        assertTrue(targets.none { it.tsCode == "600001.SH" }, "信号日涨停票不进任何结果")
        val selected = targets.filter { it.selected }.map { it.tsCode }
        assertEquals(listOf("600002.SH", "600003.SH"), selected, "剔除涨停后从剩余取 Top2")
    }

    @Test
    fun `窗口末行不是信号日 T 的票被剔除（防未来函数 停牌）`() = runTest {
        val universe = listOf("600001.SH", "600002.SH")
        val windows = mapOf(
            // 600001 末行落在 T-1（当日停牌，数据未到 T）→ 不可选
            "600001.SH" to window("600001.SH", dailyDelta = 0.50, endDate = LocalDate(2026, 4, 29)),
            "600002.SH" to window("600002.SH", dailyDelta = 0.30),
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 5,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        assertTrue(targets.none { it.tsCode == "600001.SH" }, "末行非信号日 T 的票被剔除")
        assertEquals(listOf("600002.SH"), targets.filter { it.selected }.map { it.tsCode })
    }

    @Test
    fun `预热不足（行数小于 emaSpan+1）被剔除`() = runTest {
        val universe = listOf("600001.SH", "600002.SH")
        val windows = mapOf(
            // 600001 只有 15 行 < emaSpan(20)+1，预热不足
            "600001.SH" to window("600001.SH", dailyDelta = 0.50, len = 15),
            "600002.SH" to window("600002.SH", dailyDelta = 0.30, len = 60),
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 5,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        assertTrue(targets.none { it.tsCode == "600001.SH" }, "预热不足票被剔除")
        assertEquals(listOf("600002.SH"), targets.filter { it.selected }.map { it.tsCode })
    }

    @Test
    fun `候选不足 topN 时只选出存活票不报错`() = runTest {
        val universe = listOf("600001.SH")
        val windows = mapOf("600001.SH" to window("600001.SH", dailyDelta = 0.30))
        val selector = Ema20SlopeTargetSelector(
            topN = 5,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        // 只有 1 只存活，selected=1，权重 1/1=1.0（等权分母用 selected 只数）
        assertEquals(1, targets.count { it.selected })
        assertTrue(kotlin.math.abs(targets.single { it.selected }.targetWeight - 1.0) < 1e-9, "候选不足时等权分母=存活只数")
    }

    /** 注入桩数据源，零 DB 依赖。 */
    private class FakeEma20DataSource(
        private val windows: Map<String, List<ProductionOhlcvWindowRow>>,
        private val limitUp: Set<String> = emptySet(),
    ) : Ema20SelectionDataSource {
        override fun loadLimitUpSymbols(tradeDate: LocalDate): Set<String> = limitUp
        override fun loadCloseQfqWindows(
            tsCodes: List<String>,
            endDateInclusive: LocalDate,
            limitPerStock: Int,
        ): Map<String, List<ProductionOhlcvWindowRow>> = windows
    }

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
        reason = "test",
    )
}
