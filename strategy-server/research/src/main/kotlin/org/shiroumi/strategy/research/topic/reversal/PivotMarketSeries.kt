package org.shiroumi.strategy.research.topic.reversal

import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.strategy.research.topic.factor.source.ResearchKlineSource

/**
 * 市场级 OHLC 路径序列 —— 反转研究的「价形态原料」聚合器。
 *
 * 设计依据：`private/research-docs/pivot-reversal-formula.html` §1/§2/§4。
 * 与量价 topic 的 [org.shiroumi.database.sentiment.VolumePriceMarketCalculator] 平级，但口径不同：
 * - 量价基础序列（VPM_ret / VPM_turn）取**全市场等权**，关注「市场整体量能节奏」；
 * - OHLC 路径因子取**全市场流通市值加权**（对齐趋势研究的市场口径），关注「市场作为一个整体当天有没有由强转弱」。
 *
 * 关键事实（§1）：反转标签是「市场当天的日内顶部反转结构」，必须用到**开盘价 + 昨收**（高开低走 = 相对昨收
 * 高开、收盘砸弱）。每只股票用它**自身的前一日收盘**算日内路径，再按当日流通市值加权聚合 —— 与
 * [org.shiroumi.database.sentiment.VolumePriceMarketCalculator] 用 `previousCloseQfq` 完全同源，免疫复权与个股缺口。
 *
 * 因果纪律：本类只产出「每个交易日一行」的市场级路径值，不做任何跨日推导（一阶差分、z 分、标签对齐
 * 都属于「研究内容」，由 [PivotReversalFeatures] 在这些序列上完成）。前复权价非正、无昨收、无振幅的标的剔除。
 */
object PivotMarketSeries {

    /**
     * 市场级单日路径快照（流通市值加权聚合后的「市场当日 K 线形态」）。
     *
     * 全部使用前复权价（QFQ）保证跨期可比；缺失前复权字段时回退原始价。
     * @property retOpen   市场级跳空：每股 (开 − 昨收) / 昨收 的市值加权，正为高开。
     * @property rIntra    日内回落 R_intra = 每股 (收 − 开) / 昨收 的市值加权，高开低走为负（§2 核心方向因子）。
     * @property pClose    收盘位置 P_close = 每股 (收 − 低) / (高 − 低) 的市值加权 ∈ [0,1]，弱收盘趋近 0。
     * @property sUpper    上影强度 S_upper = 每股 (高 − max(开,收)) / (高 − 低) 的市值加权，冲高回落越多越大。
     * @property dOc       开收背离 D_oc = 每股 (开 − 收) / 昨收 的市值加权，高开低走为正（与 rIntra 反号、独立刻画）。
     */
    data class DaySnapshot(
        val tradeDate: LocalDate,
        val retOpen: Double,
        val rIntra: Double,
        val pClose: Double,
        val sUpper: Double,
        val dOc: Double,
        // —— 双向·未来 7 日反转标签所需：市场级相对昨收的收益（用于序列层累乘成市场指数水平）+ 日振幅 ——
        /** 市场级收盘收益 (收 − 昨收)/昨收 的市值加权（= 市场指数日收益，序列层累乘成 closeLevel）。 */
        val retClose: Double,
        /** 市场级最高相对昨收 (高 − 昨收)/昨收 的市值加权（序列层累乘成 highLevel，供未来窗最大反弹）。 */
        val retHigh: Double,
        /** 市场级最低相对昨收 (低 − 昨收)/昨收 的市值加权（序列层累乘成 lowLevel，供未来窗最大回撤）。 */
        val retLow: Double,
        /** 市场级日振幅 (高 − 低)/昨收 的市值加权（ATR 自适应阈值原料）。 */
        val atrPct: Double,
        // —— 横截面分布矩（聚合单值之外的「市场分歧」维度，市场级日频边界内的正交新信息）——
        /** 个股收盘收益的横截面标准差（离散度=分歧大小）。 */
        val xsStd: Double,
        /** 横截面偏度（左偏=多数微涨少数暴跌的尾部风险结构）。 */
        val xsSkew: Double,
        /** 横截面峰度（肥尾=极端个股增多）。 */
        val xsKurt: Double,
        /** 下跌家数占比（广度：多少股在跌）。 */
        val xsDownRatio: Double,
        /** 市值加权收益 − 等权收益（>0=权重股撑指数而多数股弱，背离=指数虚高）。 */
        val xsWeightDiv: Double,
        // —— 个股级「日内疲态」横截面广度（市值加权聚合会正负对冲掉的正交信息；2026-05-31）——
        // 次日大阴线的真正前兆可能是：今天**有多大比例个股**已显日内见顶/弱收盘形态。市场级加权均值看不到广度。
        /** 冲高回落广度：上影占全程 >0.5（冲高大半被打回）的个股家数占比。 */
        val xsUpperFadeRatio: Double,
        /** 弱收盘广度：收盘位置 P_close <0.3（收在当日下三分之一）的个股家数占比。 */
        val xsWeakCloseRatio: Double,
        /** 日内转弱广度：个股 (收−开)/昨收 < −1%（自身日内大幅杀跌）的家数占比。 */
        val xsIntraDropRatio: Double,
    )

    /**
     * 读取 [start, end] 内逐交易日的市场级路径快照（升序）。
     *
     * 实现：按标的批量拉日线序列，每股用自身前复权收盘的前一日值作昨收，逐日累加到市值加权器。
     * 注意昨收来自序列内前一交易日，故每个标的序列的首日无昨收、自然跳过（不影响市场级聚合）。
     */
    fun load(start: LocalDate, end: LocalDate): List<DaySnapshot> {
        val symbols = ResearchKlineSource.activeSymbols()
        // 每个交易日的市值加权累加器
        val acc = LinkedHashMap<LocalDate, Accumulator>()
        for (symbol in symbols) {
            val series = ResearchKlineSource.dailyCandles(symbol, start, end)
            accumulateSymbol(series, acc)
        }
        return acc.entries
            .sortedBy { it.key }
            .mapNotNull { (date, a) -> a.finish(date) }
    }

    /** 把单标的日线序列（升序）的日内路径按昨收归一后累加进每日加权器。 */
    internal fun accumulateSymbol(series: List<Candle>, acc: MutableMap<LocalDate, Accumulator>) {
        var prevClose = Double.NaN
        for (c in series) {
            val o = qfq(c.openQfq, c.open)
            val h = qfq(c.highQfq, c.high)
            val l = qfq(c.lowQfq, c.low)
            val close = qfq(c.closeQfq, c.close)
            val w = c.mvCirc.toDouble()
            val pc = prevClose
            prevClose = if (close > 0.0) close else Double.NaN
            if (pc.isNaN() || pc <= 0.0) continue                     // 序列首日或前复权异常：无昨收，跳过
            if (o <= 0.0 || h <= 0.0 || l <= 0.0 || close <= 0.0 || w <= 0.0) continue
            val range = h - l
            if (range <= 0.0) continue                                // 一字板等无振幅日：日内路径无定义，剔除

            val a = acc.getOrPut(c.date) { Accumulator() }
            a.wSum += w
            a.retOpen += w * (o - pc) / pc
            a.rIntra += w * (close - o) / pc
            a.pClose += w * (close - l) / range
            a.sUpper += w * (h - maxOf(o, close)) / range
            a.dOc += w * (o - close) / pc
            a.retClose += w * (close - pc) / pc
            // 横截面收集：个股收盘收益（等权计入，供分布矩 std/skew/kurt + 下跌占比 + 等权均值）
            val retI = (close - pc) / pc
            a.xsRets.add(retI)
            a.retSumEqual += retI
            if (retI < 0.0) a.downCount++
            // 个股级日内疲态广度计数（等权口径，不加权——广度本身就是「多少只」而非「市值多大」）。
            a.xsCount++
            if ((h - maxOf(o, close)) / range > 0.5) a.upperFadeCount++   // 上影占全程过半=冲高大半被打回
            if ((close - l) / range < 0.3) a.weakCloseCount++             // 收在当日下三分之一
            if ((close - o) / pc < -0.01) a.intraDropCount++             // 自身日内 (收−开)/昨收 杀跌 >1%
            a.retHigh += w * (h - pc) / pc
            a.retLow += w * (l - pc) / pc
            a.atrPct += w * range / pc
        }
    }

    /** 单日市值加权累加器（可变，仅在 load/accumulateSymbol 内部使用）。 */
    internal class Accumulator {
        var wSum = 0.0
        var retOpen = 0.0
        var rIntra = 0.0
        var pClose = 0.0
        var sUpper = 0.0
        var dOc = 0.0
        var retClose = 0.0
        var retHigh = 0.0
        var retLow = 0.0
        var atrPct = 0.0
        val xsRets = ArrayList<Double>(4096)   // 当日个股收益（横截面分布矩原料）
        var retSumEqual = 0.0                  // 等权收益和
        var downCount = 0                      // 下跌家数
        var xsCount = 0                        // 有效个股数（日内疲态广度分母）
        var upperFadeCount = 0                 // 冲高回落家数
        var weakCloseCount = 0                 // 弱收盘家数
        var intraDropCount = 0                 // 日内杀跌家数

        fun finish(date: LocalDate): DaySnapshot? {
            if (wSum <= 0.0) return null
            val mvWeightedRet = retClose / wSum
            val cnt = xsRets.size
            // 横截面分布矩（仅当样本足够时算高阶矩，否则置 0 中性）
            var std = 0.0; var skew = 0.0; var kurt = 0.0; var eqMean = 0.0
            if (cnt >= 30) {
                eqMean = retSumEqual / cnt
                var m2 = 0.0; var m3 = 0.0; var m4 = 0.0
                for (r in xsRets) { val d = r - eqMean; val d2 = d * d; m2 += d2; m3 += d2 * d; m4 += d2 * d2 }
                m2 /= cnt; m3 /= cnt; m4 /= cnt
                std = kotlin.math.sqrt(m2)
                if (std > 1e-9) { skew = m3 / (std * std * std); kurt = m4 / (m2 * m2) - 3.0 }
            }
            return DaySnapshot(
                tradeDate = date,
                retOpen = retOpen / wSum,
                rIntra = rIntra / wSum,
                pClose = pClose / wSum,
                sUpper = sUpper / wSum,
                dOc = dOc / wSum,
                retClose = mvWeightedRet,
                retHigh = retHigh / wSum,
                retLow = retLow / wSum,
                atrPct = atrPct / wSum,
                xsStd = std,
                xsSkew = skew,
                xsKurt = kurt,
                xsDownRatio = if (cnt > 0) downCount.toDouble() / cnt else 0.0,
                xsWeightDiv = mvWeightedRet - eqMean,   // 市值加权 − 等权（>0=权重股撑指数）
                xsUpperFadeRatio = if (xsCount > 0) upperFadeCount.toDouble() / xsCount else 0.0,
                xsWeakCloseRatio = if (xsCount > 0) weakCloseCount.toDouble() / xsCount else 0.0,
                xsIntraDropRatio = if (xsCount > 0) intraDropCount.toDouble() / xsCount else 0.0,
            )
        }
    }

    private fun qfq(qfq: Float, raw: Float): Double {
        val q = qfq.toDouble()
        return if (q > 0.0) q else raw.toDouble()
    }
}
