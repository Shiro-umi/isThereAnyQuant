package org.shiroumi.strategy.research.topic.crashstock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 个股截面通用下跌预警的样本结构 + 特征/标签装配器 —— 七段管线的 **Input 段**。
 *
 * 设计文档（私有，不开源）：private/research-docs/pivot-crash-stock-formula.html
 *
 * 与 reversal（市场级杀跌）的关系：同源（"过热→杀跌"均值回归），但维度从「市场级单序列」升到
 * 「个股 × 截面」，目标从「市场顶部反转」改为「**任意一只可投资股票未来 N 日是否发生显著下跌**」，
 * 是一个**通用、可对每只票表态**的二分类预警器（非顶底分层只对极端表态）。
 *
 * 核心设计（贯彻用户研究方针「下一状态由前窗变化预测」+「凡判断边界皆可微」）：
 * - **标签 = 绝对跌幅**（非跑输行业）：未来 fwd 日累计简单收益 ≤ −crashDrop（默认 −10%）= 杀跌事件 y=1。
 *   绝对口径才对应持仓人真正的亏损；硬阈值 crashDrop 在可微模型里会被「软标签」替代（θ_label 可学）。
 * - **特征以「变化量(Δ)」为主**（§20 实测估值 Δ 是第一主轴），辅以已验证强 level（§19 换手绝对水平）：
 *   换手骤升 / 换手短长比 / 波动抬升 / 估值偏离 / 估值变化率 / 动量加速度 / 换手 level。
 * - **门控输入 = 流动性**（log 成交额代理）：可投资域软门控 σ(γ·(liq−τ)) 过滤垃圾股，τ/γ 在模型里可学。
 * - **因果纪律**：所有特征取截面当日(t−1 已收盘已知)及更早窗；标签在未来 [1, fwd]；
 *   截面 z 化（缩尾，模型内可软化）只在当日横截面内做，不跨未来。
 */
object PivotCrashStockSample {

    /**
     * 一条「个股 × 截面」样本：特征在 di（t−1 已知），标签看未来。
     *
     * @property crashLabel y ∈ {0,1}：未来 fwd 日累计简单收益 ≤ −crashDrop。
     * @property futureDrop 未来 fwd 日累计简单收益（用于软标签 θ_label 可学；负=下跌）。
     * @property liquidity  门控输入：log 流通成交额代理（越低越像垃圾股），原始值，z 化在装配时做。
     * @property invest     硬可投资标记（ST/次新/极小流动性），仅供「可投资域评估」过滤，不进特征。
     * @property features   已横截面 z 化的特征向量（顺序与 FEATURE_NAMES 对齐）。
     */
    data class Sample(
        val tsCode: String,
        val tradeDate: LocalDate,
        val futureDrop: Double,
        val liquidity: Double,
        val invest: Boolean,
        val features: DoubleArray,
        // —— 三种标签口径（≤7天短持仓，对比哪个信号最纯）——
        val labelAbs: Int,     // 绝对急跌：未来 fwd 日累计简单收益 ≤ −crashDrop
        val labelRel: Int,     // 相对下跌：个股未来 fwd 日 − 市场等权未来 fwd 日 ≤ −relDrop（剥β）
        val labelResid: Int,   // 剥β绝对：个股 − 市场（β=1 简化残差）≤ −crashDrop 且 个股本身也跌
    ) {
        /** 按口径取有效标签。 */
        fun labelBy(kind: LabelKind): Int = when (kind) {
            LabelKind.ABS -> labelAbs
            LabelKind.REL -> labelRel
            LabelKind.RESID -> labelResid
        }
    }

    /** 标签口径：绝对急跌 / 相对下跌(剥β) / 剥β残差。 */
    enum class LabelKind { ABS, REL, RESID }

    /** 特征顺序（全模型唯一锚点）。Δ 为主 + 换手 level。 */
    val FEATURE_NAMES = listOf(
        "换手·骤升",    // 今日换手 / 过去longW均换手
        "换手·短长比",  // 近shortW均换手 / 近longW均换手
        "波动·抬升",    // 近shortW σ / 近longW σ
        "估值·偏离",    // (PE − peHistW均PE) / 均PE
        "估值·变化率",  // (PE − longW前PE) / longW前PE
        "动量·加速度",  // 近shortW动量 − 近longW动量×(shortW/longW)
        "换手·level",   // ln(今日换手)（§19 验证绝对水平更强，保留为对照/补充轴）
    )
    val NF = FEATURE_NAMES.size

    /** 个股逐日时序的紧凑视图（装配输入）：di -> 各原料。 */
    class StockSeries {
        val ret = HashMap<Int, Double>()    // di -> 当日对数收益
        val tnr = HashMap<Int, Double>()    // di -> 换手率
        val pe = HashMap<Int, Double>()     // di -> PE-TTM（>0）
        val amount = HashMap<Int, Double>() // di -> 成交额代理（流动性门控用）
    }

    /**
     * 装配样本集：把每只股的时序折叠成「di 特征 → 未来 fwd 标签」样本流，并在每个截面内 z 化。
     *
     * @param seriesByTs 个股 -> 时序
     * @param diList     升序交易日索引轴（与 series 的 di 对齐）
     * @param profiles   静态画像（ST/次新过滤）：tsCode -> (isSt, listDi)
     * @param fwd        未来窗（默认 20）
     * @param crashDrop  杀跌阈值（默认 0.10 = −10%）
     * @param shortW/longW/peHistW Δ 窗口
     * @param minPerCross 截面最少样本（不足跳过）
     */
    fun assemble(
        seriesByTs: Map<String, StockSeries>,
        diList: List<Int>,
        dateOfDi: Map<Int, LocalDate>,           // di -> 真实交易日（被预测日 t 的特征锚点 di）
        stProfile: Map<String, Boolean>,         // tsCode -> 是否 ST（名称含 ST/*ST）
        listDiByTs: Map<String, Int>,            // tsCode -> 上市日的 di（次新过滤）
        fwd: Int = 5,                            // ≤7 天短持仓（用户钉死方向）
        crashDrop: Double = 0.10,                // 绝对急跌阈值（labelAbs）
        relDrop: Double = 0.05,                  // 相对下跌阈值（labelRel/Resid，剥β后阈值更小）
        shortW: Int = 5,
        longW: Int = 20,
        peHistW: Int = 60,
        newStockBars: Int = 60,                  // 上市后 newStockBars 根内算次新（不可投资）
        minPerCross: Int = 100,
    ): List<Sample> {
        val warm = maxOf(longW, peHistW) + 1
        // —— 预算市场等权未来 fwd 日累计简单收益（剥β标签用）——
        // 先聚合每个 di 的全市场等权日收益，再前向累加 fwd 日转简单收益。
        val mktDayRet = HashMap<Int, Double>()
        run {
            val sum = HashMap<Int, Double>(); val cnt = HashMap<Int, Int>()
            for (s in seriesByTs.values) for ((di, r) in s.ret) { sum[di] = (sum[di] ?: 0.0) + r; cnt[di] = (cnt[di] ?: 0) + 1 }
            for ((di, c) in cnt) if (c >= 20) mktDayRet[di] = sum[di]!! / c
        }
        // marketFwd[di] = 市场未来 [di+1, di+fwd] 累计简单收益；窗口不全则缺。
        val marketFwd = HashMap<Int, Double>()
        for (di in diList) {
            var logSum = 0.0; var ok = true
            for (k in 1..fwd) { val r = mktDayRet[di + k]; if (r == null) { ok = false; break }; logSum += r }
            if (ok) marketFwd[di] = kotlin.math.exp(logSum) - 1.0
        }
        // 截面彼此独立 → 按截面并行（M4 多核近线性加速装配）。每个 di 产出一批 Sample，最后 flatten。
        val targetDis = diList.filter { it >= warm }
        return runBlocking(Dispatchers.Default) {
            targetDis.map { di ->
                async {
                    assembleOneCross(
                        di, seriesByTs, dateOfDi, stProfile, listDiByTs, marketFwd,
                        fwd, crashDrop, relDrop, shortW, longW, peHistW, newStockBars, minPerCross,
                    )
                }
            }.awaitAll().flatten()
        }
    }

    /** 单截面装配（纯函数、无共享可变状态 → 可安全并行）。 */
    private fun assembleOneCross(
        di: Int,
        seriesByTs: Map<String, StockSeries>,
        dateOfDi: Map<Int, LocalDate>,
        stProfile: Map<String, Boolean>,
        listDiByTs: Map<String, Int>,
        marketFwd: Map<Int, Double>,
        fwd: Int, crashDrop: Double, relDrop: Double, shortW: Int, longW: Int, peHistW: Int,
        newStockBars: Int, minPerCross: Int,
    ): List<Sample> {
            val raws = ArrayList<Raw>()
            for ((ts, s) in seriesByTs) {
                val tnrToday = s.tnr[di] ?: continue
                val peToday = s.pe[di] ?: continue
                // —— Δ 原料（全部截至 di-1 的窗 / 当日已知）——
                val tnrAvgL = winAvg(s.tnr, di, longW) ?: continue
                if (tnrAvgL <= 0) continue
                val f0 = tnrToday / tnrAvgL                              // 换手骤升
                val tnrAvgS = winAvg(s.tnr, di, shortW) ?: continue
                val f1 = tnrAvgS / tnrAvgL                              // 换手短长比
                val volS = winVol(s.ret, di, shortW) ?: continue
                val volL = winVol(s.ret, di, longW) ?: continue
                if (volL <= 0) continue
                val f2 = volS / volL                                    // 波动抬升
                val peAvgH = winAvg(s.pe, di, peHistW) ?: continue
                if (peAvgH <= 0) continue
                val f3 = (peToday - peAvgH) / peAvgH                    // 估值偏离
                val pePrev = s.pe[di - longW] ?: continue
                if (pePrev <= 0) continue
                val f4 = (peToday - pePrev) / pePrev                    // 估值变化率
                val momS = winSum(s.ret, di, shortW) ?: continue
                val momL = winSum(s.ret, di, longW) ?: continue
                val f5 = momS - momL * (shortW.toDouble() / longW)      // 动量加速度
                val f6 = ln(tnrToday + 1e-6)                            // 换手 level
                // —— 标签：未来 fwd 日累计简单收益（≤7天短持仓）——
                var logSum = 0.0; var ok = true
                for (k in 1..fwd) { val r = s.ret[di + k]; if (r == null) { ok = false; break }; logSum += r }
                if (!ok) continue
                val drop = kotlin.math.exp(logSum) - 1.0               // 个股未来 fwd 简单收益
                val mkt = marketFwd[di] ?: continue                    // 市场未来 fwd 简单收益
                val excess = drop - mkt                                // 剥β相对收益
                val labelAbs = if (drop <= -crashDrop) 1 else 0        // 绝对急跌
                val labelRel = if (excess <= -relDrop) 1 else 0        // 相对下跌（跑输市场）
                val labelResid = if (excess <= -relDrop && drop < 0) 1 else 0  // 剥β残差且本身下跌
                // —— 流动性门控输入 ——
                val amt = s.amount[di] ?: continue
                val liqRaw = ln(amt + 1.0)
                // —— 硬可投资标记（ST/次新/极小流动性）——
                val isSt = stProfile[ts] == true
                val listDi = listDiByTs[ts]
                val isNew = listDi != null && (di - listDi) < newStockBars
                val invest = !isSt && !isNew
                raws.add(Raw(ts, doubleArrayOf(f0, f1, f2, f3, f4, f5, f6), liqRaw, drop, labelAbs, labelRel, labelResid, invest))
            }
            if (raws.size < minPerCross) return emptyList()
            // —— 截面 z 化（每个特征列）——
            val zCols = Array(NF) { c -> zscore(raws.map { it.f[c] }) }
            val date = dateOfDi[di] ?: return emptyList()
            return ArrayList<Sample>(raws.size).apply {
                for (i in raws.indices) {
                    val r = raws[i]
                    val feat = DoubleArray(NF) { c -> zCols[c][i] }
                    add(Sample(r.ts, date, r.drop, r.liqRaw, r.invest, feat, r.labelAbs, r.labelRel, r.labelResid))
                }
            }
    }

    private data class Raw(
        val ts: String, val f: DoubleArray, val liqRaw: Double, val drop: Double,
        val labelAbs: Int, val labelRel: Int, val labelResid: Int, val invest: Boolean,
    )

    private fun winAvg(m: HashMap<Int, Double>, di: Int, w: Int): Double? {
        var s = 0.0; for (k in (di - w) until di) { val v = m[k] ?: return null; s += v }; return s / w
    }
    private fun winSum(m: HashMap<Int, Double>, di: Int, w: Int): Double? {
        var s = 0.0; for (k in (di - w) until di) { val v = m[k] ?: return null; s += v }; return s
    }
    private fun winVol(m: HashMap<Int, Double>, di: Int, w: Int): Double? {
        val rs = ArrayList<Double>(w); for (k in (di - w) until di) { val r = m[k] ?: return null; rs.add(r) }
        if (rs.size < w) return null
        val mean = rs.average(); return sqrt(rs.sumOf { (it - mean) * (it - mean) } / (rs.size - 1))
    }
    private fun zscore(xs: List<Double>): DoubleArray {
        val mean = xs.average()
        val sd = sqrt(xs.sumOf { (it - mean) * (it - mean) } / maxOf(1, xs.size - 1))
        if (sd == 0.0) return DoubleArray(xs.size)
        return DoubleArray(xs.size) { ((xs[it] - mean) / sd).coerceIn(-5.0, 5.0) }
    }
}
