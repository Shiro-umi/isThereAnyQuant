package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockDailyCandleRepository
import utils.logger

/**
 * 全历史重建专用的滑窗日线供给器——以分段预取替代持仓推进逐日两次按交易日查库。
 *
 * 性能动机：在线盘后 [PostMarketPreparationJob.advanceHoldings] 对每个交易日做两次
 * `findByTradeDate`（当日蜡烛 + 前一交易日蜡烛作信号日基准）。逐日重建 N 个交易日 → 约 2N 次按日全表
 * 扫描；且相邻两日里「前一日蜡烛」与「上一轮的当日蜡烛」是同一天，被扫两次（2 倍冗余）。
 *
 * 本供给器把整段重建区间切成 ~[SEGMENT_TRADING_DAYS] 个交易日一段，每段一次性
 * [StockDailyCandleRepository.findByDateRange] 预取「段首前一交易日 .. 段尾」全部蜡烛，按 tradeDate →
 * (tsCode → Candle) 索引缓存；段内逐日只读缓存，零额外 DB 往返。每段前置一个交易日，保证段首那天
 * 持仓推进取「前一交易日信号日蜡烛」时窗内不缺。
 *
 * 数值等价：[StockDailyCandleRepository.findByDateRange] 与 `findByTradeDate` 共用同一
 * `ResultRow.toCandle()`，不裁列、不改值 → 持仓推进的入场价/止盈止损判定数值严格不变。
 *
 * 串行假设：本供给器为单消费者（orchestrator 逐日 forEach）设计，缓存非线程安全，
 * 与「持仓链严格串行、不并行」约束一致。
 *
 * @param tradeDates 重建区间内的有序交易日列表（升序，由 orchestrator 传入）。
 */
class SlidingWindowCandleProvider(
    private val tradeDates: List<LocalDate>,
) : (LocalDate) -> Map<String, Candle> {

    private val logger by logger("SlidingWindowCandleProvider")

    /** 当前已加载段的逐日蜡烛索引：tradeDate → (tsCode → Candle)。仅缓存一段，跨段即换出。 */
    private var loadedSegment: Map<LocalDate, Map<String, Candle>> = emptyMap()

    /** 当前已加载段的下标（在 [segments] 中）；-1 = 尚未加载任何段。 */
    private var loadedSegmentIndex: Int = -1

    /**
     * 一段的预取边界：[rangeStart..rangeEnd] 闭区间。
     * rangeStart = 段首前一交易日（持仓推进读它作信号日基准），无前序交易日时退化为段首。
     */
    private data class Segment(val rangeStart: LocalDate, val rangeEnd: LocalDate, val tradingDayCount: Int)

    /**
     * 重建区间按 [SEGMENT_TRADING_DAYS] 切段，每段边界（含段首前一交易日）在构造时一次性解析，
     * 避免逐次 invoke 定位段时反复查交易日历。
     */
    private val segments: List<Segment> = tradeDates.chunked(SEGMENT_TRADING_DAYS).map { chunk ->
        val rangeStart = TradingCalendarRepository.findPreviousTradingDate(chunk.first()) ?: chunk.first()
        Segment(rangeStart = rangeStart, rangeEnd = chunk.last(), tradingDayCount = chunk.size)
    }

    override fun invoke(date: LocalDate): Map<String, Candle> {
        val segmentIndex = segments.indexOfFirst { date in it.rangeStart..it.rangeEnd }
        if (segmentIndex < 0) {
            // 落在任何段（含段首前一交易日）之外的日期（理论上不发生）：回退按日查库，保持取数完整。
            return StockDailyCandleRepository.findByTradeDate(date).associateBy { it.tsCode }
        }
        ensureSegmentLoaded(segmentIndex)
        // 段内命中缓存；段首前一交易日（非交易区间内的信号日基准）亦在预取范围内。
        return loadedSegment[date]
            ?: StockDailyCandleRepository.findByTradeDate(date).associateBy { it.tsCode }
    }

    private fun ensureSegmentLoaded(segmentIndex: Int) {
        if (segmentIndex == loadedSegmentIndex) return
        val segment = segments[segmentIndex]
        val byDate = StockDailyCandleRepository.findByDateRange(segment.rangeStart, segment.rangeEnd)
        loadedSegment = byDate.mapValues { (_, candles) -> candles.associateBy { it.tsCode } }
        loadedSegmentIndex = segmentIndex
        logger.info(
            "[滑窗预取] 段=${segmentIndex + 1}/${segments.size} 范围=[${segment.rangeStart}..${segment.rangeEnd}] " +
                "交易日=${segment.tradingDayCount} 预取日键=${byDate.size}"
        )
    }

    companion object {
        /** 每段交易日数（约一年）：兼顾单段预取内存占用与跨段查库次数。 */
        const val SEGMENT_TRADING_DAYS = 250
    }
}
