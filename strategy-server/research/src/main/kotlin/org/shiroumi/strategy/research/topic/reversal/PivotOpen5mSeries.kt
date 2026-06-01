package org.shiroumi.strategy.research.topic.reversal

import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.database.stock.StockOpen5mRepository
import org.shiroumi.strategy.research.topic.factor.source.ResearchKlineSource

/**
 * 市场级「当日开盘 5 分钟」量价序列 —— 大阴线日内预警的**正交新信息**聚合器。
 *
 * 因果定位（关键）：首根 5min bar（09:30–09:35）是 **t 日当天** 才产生的信息。此前六轮大阴线研究全用
 * 「t−1 及更早的收盘后信息」预测「t 日突发大阴线」，缺的正是「t 日开盘后市场的真实反应」。开盘集合竞价 +
 * 前 5 分钟连续竞价浓缩了隔夜消息、外盘、资金意图的首次定价——对「t 日是否大幅杀跌」是因果上正交的依据。
 *
 * 与 [PivotMarketSeries] 同口径：每股用自身**日线昨收**归一（免疫复权/缺口），按当日**流通市值加权**聚合成
 * 市场级；同时保留**横截面广度**（市值加权聚合会对冲掉的「多少股开盘即弱」）。数据源 stk_mins 自 2010 起。
 *
 * 严格因果：本类只产出「每个交易日一行」的开盘量价值；差分/z 分/与标签对齐都属研究内容，由 [PivotReversalFeatures]
 * 在序列上完成。注意：这些因子是 t 日 09:35 已知，预测的标签也是 t 日收盘——属「当日盘中预警」，与原
 * 「t−1 预测 t」是不同问题定义（在融合时不与 t−1 特征混淆因果）。
 */
object PivotOpen5mSeries {

    /**
     * 市场级单日开盘 5min 快照。所有分量按流通市值加权（除广度类为等权家数占比）。
     * @property openGap     开盘跳空 = 每股 (首根open − 昨收)/昨收 的市值加权（高开为正）。
     * @property o5Ret        开盘 5min 内涨跌 = 每股 (首根close − 首根open)/昨收 的市值加权（开盘即跌为负）。
     * @property o5Amp        开盘 5min 振幅 = 每股 (首根high − 首根low)/昨收 的市值加权（开盘波动）。
     * @property o5UpperWick  开盘上影 = 每股 (high − max(open,close))/昨收 的市值加权（冲高回落）。
     * @property o5GapFade    高开走弱 = 每股 max(0, openGap_i) 且 o5Ret_i<0 的耦合（高开但 5min 内已回落）。
     * @property xsGapDownRatio   低开广度 = 首根 open < 昨收 的家数占比。
     * @property xsO5DropRatio    开盘转弱广度 = 首根 (close−open)/昨收 < −0.3% 的家数占比。
     * @property xsO5WeakRatio    开盘弱势广度 = 首根 close 处于 [low,high] 下三分之一的家数占比。
     */
    data class DaySnapshot(
        val tradeDate: LocalDate,
        val openGap: Double,
        val o5Ret: Double,
        val o5Amp: Double,
        val o5UpperWick: Double,
        val o5GapFade: Double,
        val xsGapDownRatio: Double,
        val xsO5DropRatio: Double,
        val xsO5WeakRatio: Double,
    )

    /**
     * 读取 [start, end] 内逐交易日的市场级开盘 5min 快照（升序）。
     *
     * 实现：先批量取首根 5min（按 ts_code 分组），再用日线序列提供「昨收 + 流通市值」对齐每股开盘因子，
     * 按当日市值加权 + 等权广度聚合。无对应日线昨收/市值的股票当日跳过（与 PivotMarketSeries 同纪律）。
     */
    fun load(start: LocalDate, end: LocalDate): List<DaySnapshot> {
        // 首根 5min 按 ts_code 分组
        val open5mByCode: Map<String, List<StockOpen5mRepository.Open5mRow>> =
            StockOpen5mRepository.findRange(start, end).groupBy { it.tsCode }
        if (open5mByCode.isEmpty()) return emptyList()

        val acc = LinkedHashMap<LocalDate, Accumulator>()
        for ((code, openRows) in open5mByCode) {
            val daily = ResearchKlineSource.dailyCandles(code, start, end)
            accumulateSymbol(openRows, daily, acc)
        }
        return acc.entries.sortedBy { it.key }.mapNotNull { (date, a) -> a.finish(date) }
    }

    /** 把单标的首根 5min（按 date 索引）与日线（提供昨收+市值）对齐后累加进每日加权器。 */
    internal fun accumulateSymbol(
        openRows: List<StockOpen5mRepository.Open5mRow>,
        daily: List<Candle>,
        acc: MutableMap<LocalDate, Accumulator>,
    ) {
        if (daily.isEmpty()) return
        // 日线按日期索引 + 昨收（前一交易日 qfq 收盘；与 PivotMarketSeries 同口径用前复权免疫复权）
        val dailyByDate = HashMap<LocalDate, Candle>(daily.size)
        val prevCloseByDate = HashMap<LocalDate, Double>(daily.size)
        var prevClose = Double.NaN
        for (c in daily) {
            dailyByDate[c.date] = c
            if (!prevClose.isNaN() && prevClose > 0.0) prevCloseByDate[c.date] = prevClose
            val close = qfq(c.closeQfq, c.close)
            prevClose = if (close > 0.0) close else Double.NaN
        }
        val open5mByDate = openRows.associateBy { it.tradeDate }
        for ((date, row) in open5mByDate) {
            val pc = prevCloseByDate[date] ?: continue          // 无昨收（序列首日/缺口）跳过
            val dc = dailyByDate[date] ?: continue
            val w = dc.mvCirc.toDouble()
            if (w <= 0.0) continue
            val o = row.open.toDouble(); val h = row.high.toDouble()
            val l = row.low.toDouble(); val close = row.close.toDouble()
            if (o <= 0.0 || h <= 0.0 || l <= 0.0 || close <= 0.0) continue
            val range = h - l
            val gap = (o - pc) / pc
            val o5ret = (close - o) / pc
            val a = acc.getOrPut(date) { Accumulator() }
            a.wSum += w
            a.openGap += w * gap
            a.o5Ret += w * o5ret
            a.o5Amp += w * range / pc
            a.o5UpperWick += w * (h - maxOf(o, close)) / pc
            a.o5GapFade += w * (maxOf(0.0, gap) * if (o5ret < 0.0) -o5ret else 0.0)
            // 横截面广度（等权家数占比）
            a.xsCount++
            if (o < pc) a.gapDownCount++
            if (o5ret < -0.003) a.o5DropCount++
            if (range > 0.0 && (close - l) / range < 0.333) a.o5WeakCount++
        }
    }

    internal class Accumulator {
        var wSum = 0.0
        var openGap = 0.0
        var o5Ret = 0.0
        var o5Amp = 0.0
        var o5UpperWick = 0.0
        var o5GapFade = 0.0
        var xsCount = 0
        var gapDownCount = 0
        var o5DropCount = 0
        var o5WeakCount = 0

        fun finish(date: LocalDate): DaySnapshot? {
            if (wSum <= 0.0 || xsCount == 0) return null
            return DaySnapshot(
                tradeDate = date,
                openGap = openGap / wSum,
                o5Ret = o5Ret / wSum,
                o5Amp = o5Amp / wSum,
                o5UpperWick = o5UpperWick / wSum,
                o5GapFade = o5GapFade / wSum,
                xsGapDownRatio = gapDownCount.toDouble() / xsCount,
                xsO5DropRatio = o5DropCount.toDouble() / xsCount,
                xsO5WeakRatio = o5WeakCount.toDouble() / xsCount,
            )
        }
    }

    private fun qfq(qfq: Float, raw: Float): Double {
        val q = qfq.toDouble()
        return if (q > 0.0) q else raw.toDouble()
    }
}
