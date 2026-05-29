package org.shiroumi.strategy.research.topic.factor.source

import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.database.common.repository.StockBasicRepository
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.LimitListDRecord
import org.shiroumi.database.stock.LimitListDRepository
import org.shiroumi.database.stock.StockDailyCandleRepository

/**
 * 研究管线的 **Source 段**：把 :database 的 Repository 薄包装成研究友好的事实数据读口。
 *
 * 严格边界（见 temp/research-pipeline-foundation-todolist.md §0）：
 * - **只读【事实】数据**：日线 K 线、涨跌停事实、交易日历、活跃标的清单。
 * - **不做任何因子加工**。归一化、38 因子计算、Y 标签、状态划分都属于「研究内容」，
 *   由 autoresearch 在 [org.shiroumi.strategy.research.pipeline.ResearchStudy] 里实现，不在本段。
 * - 数据库只作事实来源；研究中间态与结论一律落文件（见 ResearchContext.workspace）。
 *
 * 性能扩展位：若研究规模下逐标的查询成为瓶颈，可在 :database 新增 `research/<topic>/` 目录
 * 实现高效批量查询（如一次性按交易日拉全市场截面），本段再切换到那批接口；当前先复用既有 Repository。
 */
object ResearchKlineSource {

    /** 研究区间内的活跃标的清单（事实）。 */
    fun activeSymbols(): List<String> = StockBasicRepository.getActiveSymbols()

    /** 研究区间内的开市交易日（含两端），用于对齐时间轴。 */
    fun openTradingDates(start: LocalDate, end: LocalDate): List<LocalDate> =
        TradingCalendarRepository.findOpenDates(start, end)

    /** 单标的、按区间读取日线事实序列（升序）。 */
    fun dailyCandles(tsCode: String, start: LocalDate, end: LocalDate): List<Candle> =
        StockDailyCandleRepository.findRange(tsCode, start, end)

    /**
     * 多标的批量读取日线事实，返回 `tsCode -> 升序日线序列`。
     *
     * 当前实现按标的逐次查询既有 Repository；如需全市场截面请走性能扩展位。
     */
    fun dailyCandles(
        tsCodes: List<String>,
        start: LocalDate,
        end: LocalDate,
    ): Map<String, List<Candle>> =
        tsCodes.associateWith { dailyCandles(it, start, end) }

    /** 单交易日的全市场日线截面（事实）。 */
    fun dailyCandlesByDate(tradeDate: LocalDate): List<Candle> =
        StockDailyCandleRepository.findByTradeDate(tradeDate)

    /** 单交易日的涨跌停事实清单。 */
    fun limitListByDate(tradeDate: LocalDate): List<LimitListDRecord> =
        LimitListDRepository.findByTradeDate(tradeDate)

    /** 按区间读取涨跌停事实（可选按标的过滤）。 */
    fun limitList(
        start: LocalDate,
        end: LocalDate,
        tsCode: String? = null,
    ): List<LimitListDRecord> =
        LimitListDRepository.findRange(start, end, tsCode = tsCode)
}
