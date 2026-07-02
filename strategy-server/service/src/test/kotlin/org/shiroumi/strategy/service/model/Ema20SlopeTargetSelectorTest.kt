package org.shiroumi.strategy.service.model

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.shiroumi.database.stock.ProductionOhlcvWindowRow
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EMA20 斜率选股器（三件套口径）单测。
 *
 * 覆盖核心正确性：
 *  - EMA20 slope10 斜率降序排序 + Top-N；等权分母恒为 topN
 *  - slope10 数值 = (ema[T]-ema[T-slopeWindow])/ema[T-slopeWindow]；slopeWindow=1 回退末两点旧口径
 *  - 信号日收盘涨停票被剔除（不进 selected）
 *  - 防未来函数：窗口末行不是信号日 T 的票被剔除（停牌/数据缺）
 *  - 预热不足（窗口行数 < emaSpan+slopeWindow+1）被剔除
 *  - G1∪G2 尾控否决门：B1 贴顶 / D1 推力超阈值否决、不递补顺位、信号不可判保留、可关断
 *  - B1/D1 信号与研究实现（temp/gate_rev_base.py b1_at/d1_at）交叉验证向量
 *  - selectionScore = EMA20 斜率（下游 entryPriority 排序依赖）、sentimentExposure 透传
 *
 * 门控测试序列的 b1/d1/slope10 期望值全部由研究 python 实现预先算出（生成过程见
 * temp/ema20_gates_combo.py 同源函数），Kotlin 实现必须与其位级一致。
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
        windowFromCloses(
            tsCode = tsCode,
            closes = DoubleArray(len) { startPrice + dailyDelta * it },
            endDate = endDate,
            allComplete = allComplete,
        )

    /** 由精确 close 序列造窗口（门控/交叉验证向量用），末行落在 [endDate]。 */
    private fun windowFromCloses(
        tsCode: String,
        closes: DoubleArray,
        endDate: LocalDate = tradeDate,
        allComplete: Boolean = true,
    ): List<ProductionOhlcvWindowRow> =
        closes.mapIndexed { i, close ->
            val offset = closes.size - 1 - i
            ProductionOhlcvWindowRow(
                tsCode = tsCode,
                tradeDate = endDate.minus(offset, DateTimeUnit.DAY),
                openQfq = close,
                highQfq = close + 0.5,
                lowQfq = close - 0.5,
                closeQfq = close,
                volumeQfq = 1000.0,
                turnoverReal = 100_000.0,
                adjustedComplete = allComplete,
            )
        }

    // ---------- 门控测试序列（b1/d1/slope10 期望值由研究 python 实现预算） ----------

    /** 每天 +0.4% 复合上行 70 根：b1=1.0（贴顶）、d1=-0.000357、slope10=+0.0407。 */
    private val steadyCloses = DoubleArray(70) { 10.0 * Math.pow(1.004, it.toDouble()) }

    /** 前 60 根线性升至 15.9、末 10 根回落 60+10 根：b1=0.8019、d1=-0.0747、slope10=-0.0386。 */
    private val peakFallCloses = DoubleArray(70) { i ->
        if (i < 60) 10.0 + 0.1 * i else 15.9 - 0.35 * (i - 60)
    }

    /** 横盘 69 根 + 单日 +9%：b1=1.0、d1=+0.0287、slope10=+0.0086。 */
    private val burstCloses = DoubleArray(70) { if (it < 69) 10.0 else 10.9 }

    /** 平台 16×10 根 → 阴跌至 12 → 末 10 根猛拉 15.2（未破前高）：b1=0.950、d1=+0.0619（纯 D1 触发）。 */
    private val vShapeCloses = DoubleArray(60) { i ->
        when {
            i < 10 -> 16.0
            i < 50 -> 16.0 - 4.0 * (i - 10) / 39.0
            else -> 12.0 + 3.2 * (i - 49) / 10.0
        }
    }

    /** 升 28 根后回落 4 根，共 32 根：b1=0.9134（不触发）、d1=NaN（<35 根不可判）、slope10 可算。 */
    private val shortPullbackCloses = doubleArrayOf(
        *DoubleArray(28) { 10.0 + 0.1 * it }, 12.2, 11.9, 11.7, 11.6,
    )

    @Test
    fun `斜率降序选 Top-N 等权且 selectionScore 等于斜率`() = runTest {
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
            gateEnabled = false,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment(exposure = 0.8))

        // 全量 5 只都打分返回，selected 只有前 3。
        assertEquals(5, targets.size, "全量候选都应返回（含未选中）")
        val selected = targets.filter { it.selected }.map { it.tsCode }
        assertEquals(listOf("600001.SH", "600002.SH", "600003.SH"), selected, "斜率最大的前 3 只被选中")

        // 等权分母恒为 topN
        assertTrue(targets.filter { it.selected }.all { abs(it.targetWeight - 1.0 / 3.0) < 1e-9 }, "selected 等权 1/topN")
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
        // 反例固化口径：比值斜率 (ema[T]-ema[T-w])/ema[T-w] 同时受绝对增量与分母价位影响。
        // 票H 起始 100、每日 +1.0（绝对增量是票L 的 3 倍余），但高价位分母压制比值斜率（日相对增速 ~1%）。
        // 票L 起始 10、每日 +0.3（日相对增速 ~3%）。若误排「绝对斜率」H 胜出；正确排「比值」L 在前。
        val universe = listOf("600100.SH", "600200.SH")
        val windows = mapOf(
            "600100.SH" to window("600100.SH", dailyDelta = 1.0, startPrice = 100.0), // 票H
            "600200.SH" to window("600200.SH", dailyDelta = 0.3, startPrice = 10.0),   // 票L
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 1,
            gateEnabled = false,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        val ordered = targets.map { it.tsCode }
        assertEquals(
            listOf("600200.SH", "600100.SH"), ordered,
            "必须按比值斜率降序：低价位但相对斜率更大的票L 排在高价位大绝对增量的票H 之前",
        )
        assertEquals(listOf("600200.SH"), targets.filter { it.selected }.map { it.tsCode }, "Top1 选票L 而非票H")
        val scoreL = targets.single { it.tsCode == "600200.SH" }.selectionScore
        val scoreH = targets.single { it.tsCode == "600100.SH" }.selectionScore
        assertTrue(scoreL > scoreH, "票L 比值斜率($scoreL) 必须 > 票H($scoreH)")
    }

    @Test
    fun `slope10 数值等于 EMA 序列 10 日比值差分`() = runTest {
        val closes = DoubleArray(60) { 10.0 + 0.3 * it }
        val selector = Ema20SlopeTargetSelector(
            topN = 1,
            gateEnabled = false,
            dataSource = FakeEma20DataSource(mapOf("600001.SH" to windowFromCloses("600001.SH", closes))),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, listOf("600001.SH"), sentiment())

        val ema = Ema20SlopeTargetSelector.ema(closes, 20)
        val expected = (ema[ema.size - 1] - ema[ema.size - 11]) / ema[ema.size - 11]
        assertTrue(
            abs(targets.single().selectionScore - expected) < 1e-12,
            "selectionScore(${targets.single().selectionScore}) 必须等于 slope10($expected)",
        )
    }

    @Test
    fun `slopeWindow=1 回退末两点旧口径`() = runTest {
        val closes = DoubleArray(60) { 10.0 + 0.3 * it }
        val selector = Ema20SlopeTargetSelector(
            topN = 1,
            slopeWindow = 1,
            gateEnabled = false,
            dataSource = FakeEma20DataSource(mapOf("600001.SH" to windowFromCloses("600001.SH", closes))),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, listOf("600001.SH"), sentiment())

        val ema = Ema20SlopeTargetSelector.ema(closes, 20)
        val expected = (ema[ema.size - 1] - ema[ema.size - 2]) / ema[ema.size - 2]
        assertTrue(
            abs(targets.single().selectionScore - expected) < 1e-12,
            "slopeWindow=1 必须复现 2026-06-29 末两点口径",
        )
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
            gateEnabled = false,
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
            gateEnabled = false,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        assertTrue(targets.none { it.tsCode == "600001.SH" }, "末行非信号日 T 的票被剔除")
        assertEquals(listOf("600002.SH"), targets.filter { it.selected }.map { it.tsCode })
    }

    @Test
    fun `预热不足（行数小于 emaSpan+slopeWindow+1）被剔除`() = runTest {
        val universe = listOf("600001.SH", "600002.SH", "600003.SH")
        val windows = mapOf(
            // 15 行：旧末两点口径也不足
            "600001.SH" to window("600001.SH", dailyDelta = 0.50, len = 15),
            // 25 行：末两点口径（>=21）够，但 slope10 口径（>=31）不足 —— 新门槛边界
            "600002.SH" to window("600002.SH", dailyDelta = 0.40, len = 25),
            "600003.SH" to window("600003.SH", dailyDelta = 0.30, len = 60),
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 5,
            gateEnabled = false,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        assertTrue(targets.none { it.tsCode == "600001.SH" }, "15 行预热不足票被剔除")
        assertTrue(targets.none { it.tsCode == "600002.SH" }, "25 行在 slope10 口径下预热不足，同样剔除")
        assertEquals(listOf("600003.SH"), targets.filter { it.selected }.map { it.tsCode })
    }

    @Test
    fun `候选不足 topN 时等权分母仍为 topN`() = runTest {
        val universe = listOf("600001.SH")
        val windows = mapOf("600001.SH" to window("600001.SH", dailyDelta = 0.30))
        val selector = Ema20SlopeTargetSelector(
            topN = 5,
            gateEnabled = false,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        // 只有 1 只存活：selected=1，但等权分母恒为 topN（其余名额 = 现金空仓，回测语义 A 口径）
        assertEquals(1, targets.count { it.selected })
        assertTrue(
            abs(targets.single { it.selected }.targetWeight - 1.0 / 5.0) < 1e-9,
            "等权分母恒为 topN=5，候选不足时空余名额留现金",
        )
    }

    @Test
    fun `门控-B1 贴顶票被否决且语义A回退口径不递补`() = runTest {
        // slope10 排序：shortPullback(+0.0489) > steady(+0.0407) > peakFall(-0.0386)。
        // Top2 = {shortPullback, steady}；steady b1=1.0 触发 B1 否决；shortPullback b1=0.913 保留。
        // gateBackfill=false 回退语义 A：第 3 名 peakFall 不得递补进 selected（否决位=现金空仓）。
        val universe = listOf("600001.SH", "600002.SH", "600003.SH")
        val windows = mapOf(
            "600001.SH" to windowFromCloses("600001.SH", steadyCloses),
            "600002.SH" to windowFromCloses("600002.SH", shortPullbackCloses),
            "600003.SH" to windowFromCloses("600003.SH", peakFallCloses),
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 2,
            gateEnabled = true,
            gateBackfill = false,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        assertEquals(listOf("600002.SH"), targets.filter { it.selected }.map { it.tsCode }, "贴顶票被否决后只留 1 只")
        val vetoed = targets.single { it.tsCode == "600001.SH" }
        assertTrue(vetoed.selectionReason.orEmpty().contains("gate-veto"), "被否决票 reason 标注 gate-veto: ${vetoed.selectionReason}")
        assertTrue(vetoed.selectionReason.orEmpty().contains("b1="), "reason 携带 b1 信号值")
        assertTrue(!vetoed.selected && vetoed.targetWeight == 0.0, "被否决票不选中、零权重")
        // 语义 A：第 3 名 peakFall 不进 selected
        assertTrue(!targets.single { it.tsCode == "600003.SH" }.selected, "gateBackfill=false 否决后不递补顺位")
        // 等权分母恒为 topN=2
        assertTrue(
            abs(targets.single { it.selected }.targetWeight - 0.5) < 1e-9,
            "否决位 = 现金空仓，留存票权重仍 1/topN",
        )
    }

    @Test
    fun `门控-语义B默认口径否决位由后继非否决票顺位补足`() = runTest {
        // 与语义 A 回退测试同一数据：Top2={shortPullback, steady}，steady 被 B1 否决。
        // 默认 gateBackfill=true：扫描第 3 名 peakFall（b1=0.802/d1=-0.0747 双安全）补入。
        // 依据 temp/ema20_falsify_recheck.py F 臂（组合 EV +0.736% vs 空仓 +0.595%，对抗审计 PASS）。
        val universe = listOf("600001.SH", "600002.SH", "600003.SH")
        val windows = mapOf(
            "600001.SH" to windowFromCloses("600001.SH", steadyCloses),
            "600002.SH" to windowFromCloses("600002.SH", shortPullbackCloses),
            "600003.SH" to windowFromCloses("600003.SH", peakFallCloses),
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 2,
            gateEnabled = true,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        assertEquals(
            listOf("600002.SH", "600003.SH"),
            targets.filter { it.selected }.map { it.tsCode },
            "否决位由第 3 名 peakFall 顺位补足",
        )
        val backfill = targets.single { it.tsCode == "600003.SH" }
        assertTrue(backfill.selectionReason.orEmpty().startsWith("ema20-slope:backfill@3"), "补位票 reason 标注排序位: ${backfill.selectionReason}")
        assertTrue(abs(backfill.targetWeight - 0.5) < 1e-9, "补位票与 Top-N 留存票同权 1/topN")
        val topKept = targets.single { it.tsCode == "600002.SH" }
        assertTrue(topKept.selectionReason.orEmpty().startsWith("ema20-slope:Top2"), "Top-N 内留存票 reason 不变")
        assertTrue(targets.single { it.tsCode == "600001.SH" }.selectionReason.orEmpty().contains("gate-veto"), "被否决票 reason 不受补位影响")
    }

    @Test
    fun `门控-补位票同样过门（连续否决时继续向下扫描）`() = runTest {
        // slope10 排序：vShape(+0.0520) > steady(+0.0407) > peakFall(-0.0386)，topN=1。
        // Top1=vShape 被 D1 否决 → 扫第 2 名 steady 也被 B1 否决 → 扫第 3 名 peakFall 补入。
        val universe = listOf("600001.SH", "600002.SH", "600003.SH")
        val windows = mapOf(
            "600001.SH" to windowFromCloses("600001.SH", vShapeCloses),
            "600002.SH" to windowFromCloses("600002.SH", steadyCloses),
            "600003.SH" to windowFromCloses("600003.SH", peakFallCloses),
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 1,
            gateEnabled = true,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        assertEquals(listOf("600003.SH"), targets.filter { it.selected }.map { it.tsCode }, "连续否决后补入第 3 名")
        assertTrue(targets.single { it.tsCode == "600001.SH" }.selectionReason.orEmpty().contains("gate-veto"), "Top-1 否决票标注 gate-veto")
        assertTrue(
            targets.single { it.tsCode == "600002.SH" }.selectionReason.orEmpty().contains("gate-veto"),
            "扫描中被否决的补位候选同样标注 gate-veto（补位票必须过门）",
        )
        assertTrue(abs(targets.single { it.selected }.targetWeight - 1.0) < 1e-9, "补位票权重 1/topN")
    }

    @Test
    fun `门控-补位扫描后仍不足时留现金空位`() = runTest {
        // 全部候选都触发否决：vShape(D1)、steady(B1)，topN=2 → selected 为空、全部零权重。
        val universe = listOf("600001.SH", "600002.SH")
        val windows = mapOf(
            "600001.SH" to windowFromCloses("600001.SH", vShapeCloses),
            "600002.SH" to windowFromCloses("600002.SH", steadyCloses),
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 2,
            gateEnabled = true,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        assertEquals(0, targets.count { it.selected }, "全否决且无可补票时 selected 为空（现金空仓）")
        assertTrue(targets.all { it.targetWeight == 0.0 }, "全部零权重")
        assertTrue(targets.all { it.selectionReason.orEmpty().contains("gate-veto") }, "两票都标注 gate-veto")
    }

    @Test
    fun `门控-D1 推力票被否决（贴顶度未触发）`() = runTest {
        // vShape: b1=0.950 < 0.965903 不触发；d1=+0.0619 > 0.004666 触发 → 纯 D1 否决。
        // peakFall: b1=0.802 / d1=-0.0747 双安全 → 保留。
        val universe = listOf("600001.SH", "600002.SH")
        val windows = mapOf(
            "600001.SH" to windowFromCloses("600001.SH", vShapeCloses),
            "600002.SH" to windowFromCloses("600002.SH", peakFallCloses),
        )
        val selector = Ema20SlopeTargetSelector(
            topN = 2,
            gateEnabled = true,
            dataSource = FakeEma20DataSource(windows = windows),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, universe, sentiment())

        assertEquals(listOf("600002.SH"), targets.filter { it.selected }.map { it.tsCode }, "D1 推力票被否决")
        val vetoed = targets.single { it.tsCode == "600001.SH" }
        assertTrue(vetoed.selectionReason.orEmpty().contains("gate-veto"), "reason 标注 gate-veto")
        assertTrue(vetoed.selectionReason.orEmpty().contains("d1="), "reason 携带 d1 信号值")
    }

    @Test
    fun `门控-信号不可判（D1 序列不足）时保留`() = runTest {
        // shortPullback 32 根：b1=0.913 不触发、d1 不足 35 根不可判（NaN）→ 保留（与研究 NaN 语义一致）。
        val selector = Ema20SlopeTargetSelector(
            topN = 1,
            gateEnabled = true,
            dataSource = FakeEma20DataSource(
                mapOf("600001.SH" to windowFromCloses("600001.SH", shortPullbackCloses)),
            ),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, listOf("600001.SH"), sentiment())

        assertEquals(listOf("600001.SH"), targets.filter { it.selected }.map { it.tsCode }, "信号不可判的票保留")
    }

    @Test
    fun `门控-slopeWindow=1 回退时门强制关闭（防配置耦合事故）`() = runTest {
        // 门阈值在 slope10 池上定标、组合核账证明门在末两点乖离率池上失效反噬——
        // 应急回退 slopeWindow=1 时即使 gateEnabled=true 也必须强制关门。
        val selector = Ema20SlopeTargetSelector(
            topN = 1,
            slopeWindow = 1,
            gateEnabled = true,
            dataSource = FakeEma20DataSource(
                mapOf("600001.SH" to windowFromCloses("600001.SH", steadyCloses)),
            ),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, listOf("600001.SH"), sentiment())

        assertEquals(
            listOf("600001.SH"), targets.filter { it.selected }.map { it.tsCode },
            "回退口径下贴顶票（b1=1.0）不得被门否决——门必须随 slopeWindow=1 自动失效",
        )
    }

    @Test
    fun `门控-gateEnabled=false 时贴顶票不被否决`() = runTest {
        val selector = Ema20SlopeTargetSelector(
            topN = 1,
            gateEnabled = false,
            dataSource = FakeEma20DataSource(
                mapOf("600001.SH" to windowFromCloses("600001.SH", steadyCloses)),
            ),
        )

        val targets = selector.generateTargets(tradeDate, targetDate, listOf("600001.SH"), sentiment())

        assertEquals(listOf("600001.SH"), targets.filter { it.selected }.map { it.tsCode }, "关门后贴顶票正常入选")
    }

    @Test
    fun `slope10 与研究实现交叉验证（独立于 Kotlin ema 的期望值）`() = runTest {
        // 期望值由研究 python 实现独立预算（防「用自己的 ema 验自己」的自证性）：
        // steady slope10=+0.040672860262552、peakFall slope10=-0.038565948184093。
        val selector = Ema20SlopeTargetSelector(
            topN = 2,
            gateEnabled = false,
            dataSource = FakeEma20DataSource(
                mapOf(
                    "600001.SH" to windowFromCloses("600001.SH", steadyCloses),
                    "600002.SH" to windowFromCloses("600002.SH", peakFallCloses),
                ),
            ),
        )
        val targets = selector.generateTargets(tradeDate, targetDate, listOf("600001.SH", "600002.SH"), sentiment())
        assertTrue(abs(targets.single { it.tsCode == "600001.SH" }.selectionScore - 0.040672860262552) < 1e-9)
        assertTrue(abs(targets.single { it.tsCode == "600002.SH" }.selectionScore - (-0.038565948184093)) < 1e-9)
    }

    @Test
    fun `B1 D1 信号与研究实现交叉验证`() {
        // 期望值由研究 python 实现（temp/gate_rev_base.py b1_at/d1_at 逐字同源函数）对同序列预算，
        // 同 IEEE754 双精度、同算法字节序 → 位级一致（容差 1e-9 仅防平台舍入差）。
        assertTrue(abs(Ema20SlopeTargetSelector.b1TopProximity(steadyCloses) - 1.0) < 1e-9)
        assertTrue(abs(Ema20SlopeTargetSelector.d1MacdImpulse(steadyCloses) - (-0.000357272790)) < 1e-9)

        assertTrue(abs(Ema20SlopeTargetSelector.b1TopProximity(peakFallCloses) - 0.801886792453) < 1e-9)
        assertTrue(abs(Ema20SlopeTargetSelector.d1MacdImpulse(peakFallCloses) - (-0.074671568845)) < 1e-9)

        assertTrue(abs(Ema20SlopeTargetSelector.b1TopProximity(burstCloses) - 1.0) < 1e-9)
        assertTrue(abs(Ema20SlopeTargetSelector.d1MacdImpulse(burstCloses) - 0.028717948718) < 1e-9)

        assertTrue(abs(Ema20SlopeTargetSelector.b1TopProximity(vShapeCloses) - 0.95) < 1e-9)
        assertTrue(abs(Ema20SlopeTargetSelector.d1MacdImpulse(vShapeCloses) - 0.061858712011) < 1e-9)

        // 不可判边界：b1 需 >=5 根、d1 需 >=35 根
        assertTrue(Ema20SlopeTargetSelector.b1TopProximity(doubleArrayOf(10.0, 10.1, 10.2, 10.3)).isNaN())
        assertTrue(Ema20SlopeTargetSelector.d1MacdImpulse(DoubleArray(34) { 10.0 + 0.01 * it }).isNaN())
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
