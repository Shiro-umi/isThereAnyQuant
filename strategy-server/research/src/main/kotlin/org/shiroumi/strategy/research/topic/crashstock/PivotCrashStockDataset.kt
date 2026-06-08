package org.shiroumi.strategy.research.topic.crashstock

import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.repository.StockBasicRepository
import org.shiroumi.database.stock.StockDailyCandleRepository
import kotlin.math.abs
import kotlin.math.ln

/**
 * 个股截面下跌预警的 **Source 段**：把 :database 的全市场日 K 投影 + 标的画像，
 * 装配成 [PivotCrashStockSample.assemble] 所需的「个股时序 + di/date 映射 + 画像」。
 *
 * 只读事实数据（日 K 投影、标的静态画像），不做因子加工（因子在 Sample 段）。
 * 复用 [StockDailyCandleRepository.streamCloseForAggregation]（轻量投影，含 close/turnover/peTtm/mvCirc）。
 */
object PivotCrashStockDataset {

    data class Loaded(
        val seriesByTs: Map<String, PivotCrashStockSample.StockSeries>,
        val diList: List<Int>,
        val dateOfDi: Map<Int, LocalDate>,
        val stProfile: Map<String, Boolean>,
        val listDiByTs: Map<String, Int>,
    )

    /**
     * 读区间内全市场日 K 投影并装配。
     *
     * 流动性门控代理：用 ln(流通市值)。大市值 ⇒ 流动性好 ⇒ 可投资域内侧；极小市值 ⇒ 垃圾股侧。
     * （成交额更直接，但投影未带；流通市值是稳定且强相关的代理，足够供软门控 τ/γ 学边界。）
     */
    fun load(start: LocalDate, end: LocalDate): Loaded {
        val proj = StockDailyCandleRepository.streamCloseForAggregation(start, end)

        // di 轴：全市场出现过的交易日升序编号
        val dates = sortedSetOf<LocalDate>(); proj.forEach { dates.add(it.tradeDate) }
        val diOfDate = dates.withIndex().associate { (i, d) -> d to i }
        val dateOfDi = dates.withIndex().associate { (i, d) -> i to d }
        val diList = dates.indices.toList()

        // 个股时序（投影已按 ts_code+date 升序）
        val seriesByTs = HashMap<String, PivotCrashStockSample.StockSeries>()
        run {
            var curTs: String? = null; var prevClose: Double? = null
            for (p in proj) {
                val di = diOfDate[p.tradeDate]!!
                val s = seriesByTs.getOrPut(p.tsCode) { PivotCrashStockSample.StockSeries() }
                p.turnover?.let { s.tnr[di] = it }
                p.peTtm?.let { s.pe[di] = it }
                p.mvCirc?.let { s.amount[di] = it }   // 流动性代理 = 流通市值（Sample 内取 ln）
                if (p.tsCode != curTs) { curTs = p.tsCode; prevClose = if (p.closeQfq > 0) p.closeQfq else null; continue }
                val pc = prevClose; val close = p.closeQfq
                if (pc != null && pc > 0 && close > 0) {
                    val r = ln(close / pc); if (r.isFinite() && abs(r) < 0.5) s.ret[di] = r
                }
                prevClose = if (close > 0) close else prevClose
            }
        }

        // 标的画像：ST（名称含 ST）+ 上市日 di（次新过滤）
        val profiles = StockBasicRepository.findProfiles()
        val stProfile = HashMap<String, Boolean>()
        val listDiByTs = HashMap<String, Int>()
        for (pf in profiles) {
            stProfile[pf.tsCode] = pf.name.contains("ST", ignoreCase = true)
            pf.listDate?.let { ld ->
                // 上市日映射到 ≥ 它的第一个交易日 di（用于次新窗口判定）
                val di = diOfDate.entries.firstOrNull { it.key >= ld }?.value
                if (di != null) listDiByTs[pf.tsCode] = di
            }
        }
        return Loaded(seriesByTs, diList, dateOfDi, stProfile, listDiByTs)
    }
}
