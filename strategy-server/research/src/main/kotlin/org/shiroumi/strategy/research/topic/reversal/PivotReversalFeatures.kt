package org.shiroumi.strategy.research.topic.reversal

import kotlinx.datetime.LocalDate
import kotlin.math.sqrt

/**
 * 反转研究的 t−1 特征矩阵与 t 日标签装配器 —— 七段管线的 **Input 段**。
 *
 * 设计依据：`private/research-docs/pivot-reversal-formula.html` §1（标签）/ §3.1–§3.6（特征）/ §5.3（体检 B）。
 *
 * 三类输入（全部市场级、同粒度）：
 * - **价形态** M 原料：[PivotMarketSeries] 的市值加权 OHLC 路径（R_intra / P_close / S_upper / D_oc）。
 * - **量能领先** V 原料：factor 层 [org.shiroumi.strategy.research.topic.factor.study.VolumePriceFactors]
 *   在 VPM_ret / VPM_turn 上推导的 VP1 / VP2c / VP2v / VPβ（实测「t−1 超前 1 日对 t」最强领先）。
 * - **前序亢奋** G 原料：trend 研究的市场级 score P^up（只读消费）。
 *
 * 核心纪律（§5.1/§5.3）：
 * - 标签 y_t 是「t 日是否市场级日内顶部反转结构」（前 2 日累计上行 + 当天收盘比开盘低 [pivotDropThreshold]）。
 * - 所有进入预测的特征都取 **t−1 及更早**；标签留在 t。这条对齐由 [Sample] 一次性钉死，下游 Study/Eval 不再触碰原始序列。
 * - z 化（标准化）只用**截至 t−1 的扩张窗**统计量，杜绝用未来样本的均值/方差泄漏（rolling z，非全样本 z）。
 */
object PivotReversalFeatures {

    /**
     * 一条「t−1 预测 t」样本：标签在 t，特征全在 t−1。
     *
     * 价、量、亢奋三组特征均已 rolling-z 标准化（除天然有界的 pClose/score）。
     * @property label  y_t ∈ {0,1}：t 日是否市场级日内顶部反转结构。
     * @property up2    前置门：t−1、t−2 两日是否累计上行（标签定义的「前 2 日累计上行」前提，落在被预测日之前）。
     */
    data class Sample(
        val tradeDate: LocalDate,       // 被预测日 t
        val label: Int,                 // 当前研究方向（顶反 or 底反）的有效标签 y_t（由 assemble 按 direction 填）
        /** 前期趋势 regime：true=上行（顶反研究的有效域）、false=下行（底反研究的有效域）。 */
        val regimeUp: Boolean,
        /** 顶部反转标签：前期上行 且 未来 W 日最大回撤 ≥ k·ATR。 */
        val labelTop: Int,
        /** 底部反转标签：前期下行 且 未来 W 日最大反弹 ≥ k·ATR。 */
        val labelBot: Int,
        // —— 价形态 M_{t-1} 的四个 z 化分量（方向已对齐「越疲态越大」）——
        val zNegRIntra: Double,         // z(-R_intra_{t-1})
        val zPcloseGap: Double,         // z(0.5 - P_close_{t-1})
        val zSUpper: Double,            // z(S_upper_{t-1})
        val zDoc: Double,               // z(D_oc_{t-1})
        // —— 二阶恶化 Δ_{t-1}（断裂感，负=恶化）——
        val deltaIntra: Double,
        // —— 量能领先 V_{t-1} 的四个 z 化分量（方向已按 IC 符号钉死）——
        val zVp1: Double,               // z(VP1_{t-1})          正向
        val zNegVp2c: Double,           // z(-VP2c_{t-1})        协同瓦解
        val zVp2v: Double,              // z(VP2v_{t-1})         正向
        val zNegVpBeta: Double,         // z(-VPβ_{t-1})         弹性走弱
        // —— 量门控原料：t−1 量速 \dot v 的 z 分 ——
        val zVdot: Double,
        // —— 前序亢奋基准 G_{t-1} 原料：t−1 trend score ∈ [0,1] ——
        val trendScore: Double,
        // —— 附加因子（t−1 rolling-z）：上游 38 情绪因子等「因子层」原料，供体检 B 探路与扩展融合 ——
        val extra: Map<String, Double> = emptyMap(),
    )

    /**
     * 装配样本集：把对齐好的各序列折叠成「t−1 特征 → t 标签」样本流（升序、跳过窗口不足项）。
     *
     * @param dates       交易日轴（升序，与所有序列等长同序）。
     * @param rIntra      市场级日内回落序列 R_intra。
     * @param pClose      市场级收盘位置序列 P_close。
     * @param sUpper      市场级上影强度序列 S_upper。
     * @param dOc         市场级开收背离序列 D_oc。
     * @param retClose    市场级收盘对数收益序列（用于标签「前 2 日累计上行」与「日内转弱」判定的市场口径）。
     * @param vp1/vp2c/vp2v/vpBeta  factor 层 VP 因子序列（市场级）。
     * @param vDot        t 当日量速 \dot v（量异动一阶差分）序列。
     * @param trendScore  trend 研究市场级 score P^up 序列（∈[0,1] 或 NaN）。
     * @param retClose/retHigh/retLow  市场级相对昨收的 收/高/低 收益（累乘成市场指数水平，供未来窗扫描）。
     * @param atrPct      市场级日振幅序列（ATR 自适应阈值原料）。
     * @param direction   研究方向：[Direction.TOP] 顶反 / [Direction.BOTTOM] 底反；决定 label 取 labelTop 还是 labelBot。
     * @param futureWin   未来窗 W（持仓周期，默认 7 个交易日）。
     * @param atrWin      ATR 平滑窗。
     * @param atrK        反转幅度阈值倍数：未来窗最大回撤/反弹 ≥ atrK × ATR 才算一次反转。
     * @param priorWin    前期趋势判定窗。
     * @param priorTrendThreshold  前期趋势显著性阈值（× ATR）：|前期累计收益| ≥ 该倍数×ATR 才算明确上行/下行 regime。
     */
    fun assemble(
        dates: List<LocalDate>,
        rIntra: List<Double?>,
        pClose: List<Double?>,
        sUpper: List<Double?>,
        dOc: List<Double?>,
        retClose: List<Double?>,
        retHigh: List<Double?>,
        retLow: List<Double?>,
        atrPct: List<Double?>,
        vp1: List<Double?>,
        vp2c: List<Double?>,
        vp2v: List<Double?>,
        vpBeta: List<Double?>,
        vDot: List<Double?>,
        trendScore: List<Double?>,
        extraSeries: Map<String, List<Double?>> = emptyMap(),
        direction: Direction = Direction.TOP,
        k: Int = 3,
        zWindow: Int = 60,
        futureWin: Int = 7,
        atrWin: Int = 14,
        atrK: Double = 1.5,
        priorWin: Int = 5,
        priorTrendThreshold: Double = 0.3,
        crashThreshold: Double = 0.01,
    ): List<Sample> {
        val n = dates.size
        // rolling-z 标准化器：对每个原始序列，z[t] 仅用 [..t] 的前序统计（含 t），杜绝未来泄漏。
        val zNegRIntraSeq = rollingZ(rIntra.mapNullable { -it }, zWindow)
        val zPcloseGapSeq = rollingZ(pClose.mapNullable { 0.5 - it }, zWindow)
        val zSUpperSeq = rollingZ(sUpper, zWindow)
        val zDocSeq = rollingZ(dOc, zWindow)
        val zVp1Seq = rollingZ(vp1, zWindow)
        val zNegVp2cSeq = rollingZ(vp2c.mapNullable { -it }, zWindow)
        val zVp2vSeq = rollingZ(vp2v, zWindow)
        val zNegVpBetaSeq = rollingZ(vpBeta.mapNullable { -it }, zWindow)
        val zVdotSeq = rollingZ(vDot, zWindow)
        val deltaSeq = secondOrderDelta(rIntra, k)
        // 附加因子（38 情绪因子等）：各自独立 rolling-z（仅用前序，无泄漏）
        val zExtra = extraSeries.mapValues { (_, seq) -> rollingZ(seq, zWindow) }

        // 市场指数水平序列（累乘相对昨收收益）：closeLevel/highLevel/lowLevel，供未来窗最大回撤/反弹扫描。
        val closeLevel = cumulativeClose(retClose)
        val highLevel = levelOnPrevClose(retHigh, closeLevel)       // 高点水平 = 前一日 close 指数 ×(1+ret_high)
        val lowLevel = levelOnPrevClose(retLow, closeLevel)
        // ATR：日振幅的简单移动平均（仅用前序，标签算阈值用）
        val atr = rollingMean(atrPct, atrWin)

        val out = ArrayList<Sample>(n)
        for (t in 1 until n) {
            val p = t - 1                                   // 预测信息日 t−1（特征只取此处及更早）

            val activeLabel: Int
            var regimeUp = false
            var labelTop = 0
            var labelBot = 0
            if (direction == Direction.CRASH) {
                // 大阴线杀跌：t 日市场加权日内 (收−开)/昨收 ≤ −crashThreshold，无 regime 前提（纯单日突发事件）。
                val intraT = rIntra[t] ?: continue
                activeLabel = if (intraT <= -crashThreshold) 1 else 0
            } else {
                val lbl = pivotLabels(
                    closeLevel, highLevel, lowLevel, retClose, atr, t,
                    futureWin, atrK, priorWin, priorTrendThreshold,
                ) ?: continue
                labelTop = lbl.top; labelBot = lbl.bot; regimeUp = lbl.regimeUp
                // 顶反研究只看上行日、底反只看下行日。
                val inDomain = if (direction == Direction.TOP) regimeUp else !regimeUp
                if (!inDomain) continue
                activeLabel = if (direction == Direction.TOP) labelTop else labelBot
            }

            val extra = if (zExtra.isEmpty()) emptyMap() else
                zExtra.mapValues { (_, s) -> s[p] ?: 0.0 }
            val sample = Sample(
                tradeDate = dates[t],
                label = activeLabel,
                regimeUp = regimeUp,
                labelTop = labelTop,
                labelBot = labelBot,
                zNegRIntra = zNegRIntraSeq[p] ?: continue,
                zPcloseGap = zPcloseGapSeq[p] ?: continue,
                zSUpper = zSUpperSeq[p] ?: continue,
                zDoc = zDocSeq[p] ?: continue,
                deltaIntra = deltaSeq[p] ?: continue,
                zVp1 = zVp1Seq[p] ?: continue,
                zNegVp2c = zNegVp2cSeq[p] ?: continue,
                zVp2v = zVp2vSeq[p] ?: continue,
                zNegVpBeta = zNegVpBetaSeq[p] ?: continue,
                zVdot = zVdotSeq[p] ?: continue,
                trendScore = trendScore[p] ?: continue,
                extra = extra,
            )
            out += sample
        }
        return out
    }

    /** 研究方向。CRASH = 大阴线杀跌（无 regime 前提的纯单日突发下跌事件）。 */
    enum class Direction { TOP, BOTTOM, CRASH }

    /** (labelTop, labelBot, regimeUp)。 */
    private data class Labels(val top: Int, val bot: Int, val regimeUp: Boolean)

    /**
     * 双向·未来 W 日反转标签（市场级，ATR 自适应阈值）：
     * - regime：前期 [priorWin] 日市场累计收益相对 ATR 显著为正 → 上行域；显著为负 → 下行域；否则中性（丢弃）。
     * - 顶反 labelTop=1 ⟺ 上行域 且 未来 W 日最大回撤 (closeLevel[t] − min lowLevel[t+1..t+W]) / closeLevel[t] ≥ atrK·ATR。
     * - 底反 labelBot=1 ⟺ 下行域 且 未来 W 日最大反弹 (max highLevel[t+1..t+W] − closeLevel[t]) / closeLevel[t] ≥ atrK·ATR。
     * 未来窗只用于**标签**（不进任何特征），严格 t−1 预测 t 起算的未来，无前视泄漏。窗口越界或缺值返回 null。
     */
    private fun pivotLabels(
        closeLevel: List<Double?>,
        highLevel: List<Double?>,
        lowLevel: List<Double?>,
        retClose: List<Double?>,
        atr: List<Double?>,
        t: Int,
        futureWin: Int,
        atrK: Double,
        priorWin: Int,
        priorTrendThreshold: Double,
    ): Labels? {
        if (t < priorWin) return null
        if (t + futureWin >= closeLevel.size) return null      // 未来窗越界：样本尾部丢弃（不可标注）
        val ct = closeLevel[t] ?: return null
        if (ct <= 0.0) return null
        val atrT = atr[t] ?: return null
        if (atrT <= 0.0) return null
        val threshold = atrK * atrT

        // 前期趋势 regime
        var priorSum = 0.0
        for (i in (t - priorWin) until t) priorSum += retClose[i] ?: return null
        val trendBand = priorTrendThreshold * atrT
        val regimeUp: Boolean = when {
            priorSum >= trendBand -> true
            priorSum <= -trendBand -> false
            else -> return null                                // 中性区：既非上行也非下行，丢弃
        }

        // 未来 W 日最大回撤 / 最大反弹（相对 t 日收盘水平）
        var minLow = Double.POSITIVE_INFINITY
        var maxHigh = Double.NEGATIVE_INFINITY
        for (j in (t + 1)..(t + futureWin)) {
            val lo = lowLevel[j] ?: return null
            val hi = highLevel[j] ?: return null
            if (lo < minLow) minLow = lo
            if (hi > maxHigh) maxHigh = hi
        }
        val maxDrawdown = (ct - minLow) / ct
        val maxRebound = (maxHigh - ct) / ct
        val top = if (regimeUp && maxDrawdown >= threshold) 1 else 0
        val bot = if (!regimeUp && maxRebound >= threshold) 1 else 0
        return Labels(top, bot, regimeUp)
    }

    /** 市场指数收盘水平：closeLevel[t] = closeLevel[t-1]·(1 + retClose[t])，起点 1.0；缺值断点产出 null 但 level 延续。 */
    private fun cumulativeClose(retClose: List<Double?>): List<Double?> {
        val out = ArrayList<Double?>(retClose.size)
        var level = 1.0
        for (r in retClose) {
            if (r == null || !r.isFinite()) { out += null; continue }
            level *= (1.0 + r)
            out += level
        }
        return out
    }

    /** 高/低水平挂在前一日 close 指数水平上：level[t] = closeLevel[t-1]·(1 + ret[t])，与 closeLevel 同一价格刻度可比。 */
    private fun levelOnPrevClose(ret: List<Double?>, closeLevel: List<Double?>): List<Double?> =
        ret.indices.map { t ->
            val r = ret[t] ?: return@map null
            if (!r.isFinite()) return@map null
            val prevClose = if (t == 0) 1.0 else (closeLevel[t - 1] ?: return@map null)
            prevClose * (1.0 + r)
        }

    /** 简单移动平均（窗 [t-win+1..t]，仅前序，不足窗 null）。 */
    private fun rollingMean(x: List<Double?>, win: Int): List<Double?> =
        x.indices.map { t ->
            if (t < win - 1) return@map null
            var sum = 0.0; var cnt = 0
            for (i in (t - win + 1)..t) { x[i]?.let { sum += it; cnt++ } }
            if (cnt < win) null else sum / cnt
        }

    /**
     * 旧单向标签（保留供单测与回溯对比，不再用于主链路）。
     */
    internal fun pivotLabel(
        retClose: List<Double?>,
        rIntra: List<Double?>,
        t: Int,
        dropThreshold: Double,
        priorWin: Int,
        priorUpThreshold: Double,
    ): Int? {
        if (t < priorWin) return null
        val intraT = rIntra[t] ?: return null
        var priorSum = 0.0
        for (i in (t - priorWin) until t) {
            priorSum += retClose[i] ?: return null
        }
        val priorUp = priorSum >= priorUpThreshold
        val intradayReversal = intraT <= -dropThreshold
        return if (priorUp && intradayReversal) 1 else 0
    }

    /**
     * 二阶恶化 Δ_{t-1}（§3.2）：R_intra 相对其之前 k 日均值的偏离（求和从 t−2 起，全程不触碰被预测日）。
     * 这里按「位置 p」直接产出整条序列：Δ[p] = R_intra[p] − mean(R_intra[p-k .. p-1])。
     * 下游融合时 A_{t-1} = G^V·max(0,−Δ)^γ 的非线性放大留给 Study（含可调 γ），本段只给原始 Δ。
     */
    internal fun secondOrderDelta(rIntra: List<Double?>, k: Int): List<Double?> =
        rIntra.indices.map { p ->
            val cur = rIntra[p] ?: return@map null
            if (p < k) return@map null
            var sum = 0.0
            var cnt = 0
            for (i in (p - k) until p) {
                rIntra[i]?.let { sum += it; cnt++ }
            }
            if (cnt < k) null else cur - sum / cnt
        }

    /**
     * Rolling-z 标准化：z[t] = (x[t] − mean_{..t}) / std_{..t}，统计量仅用扩张窗 [firstValid..t]（含 t、不含未来）。
     * 不足 [minCount] 个有效前序时产出 null。std≈0 时产出 0（无离散度，居中）。
     */
    internal fun rollingZ(x: List<Double?>, minCount: Int): List<Double?> {
        val out = ArrayList<Double?>(x.size)
        var sum = 0.0
        var sumSq = 0.0
        var cnt = 0
        for (t in x.indices) {
            val v = x[t]
            if (v == null || !v.isFinite()) { out += null; continue }
            // 用「截至 t（含 t）」的统计；含 t 不构成未来泄漏，因为该 z 值用于位置 t 自身的特征。
            sum += v; sumSq += v * v; cnt++
            if (cnt < minCount) { out += null; continue }
            val mean = sum / cnt
            val varc = (sumSq / cnt - mean * mean).coerceAtLeast(0.0)
            val std = sqrt(varc)
            out += if (std < 1e-12) 0.0 else (v - mean) / std
        }
        return out
    }

    private inline fun List<Double?>.mapNullable(op: (Double) -> Double): List<Double?> =
        map { it?.let(op) }
}
