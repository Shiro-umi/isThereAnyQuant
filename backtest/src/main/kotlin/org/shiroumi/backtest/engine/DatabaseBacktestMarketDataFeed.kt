package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.config.RulesConfig
import org.shiroumi.database.common.repository.StockBasicRepository
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockDailyCandleRepository

/**
 * stock_db 日线事实到回测执行行情的只读适配器。
 *
 * 回测执行口径固定 RAW，因此这里直接返回 `stock_daily_data` 原始 OHLCV；
 * 策略信号口径仍由策略侧确认表决定，不在此处重新计算。
 */
class DatabaseBacktestMarketDataFeed(
    private val rules: RulesConfig = RulesConfig(),
) : BacktestMarketDataFeed {
    override fun marketDataFor(date: LocalDate): DailyMarketData {
        val quotes = StockDailyCandleRepository.findByTradeDate(date)
            .associateBy { it.tsCode }
        val statuses = StockBasicRepository.findAllBacktestStatuses(
            tradeDate = date,
            excludeIpoDays = rules.excludeIpoDays,
            excludeSt = rules.excludeSt,
        )
        val previousDate = TradingCalendarRepository.findPreviousTradingDate(date)
        val preClose = previousDate
            ?.let { StockDailyCandleRepository.findByTradeDate(it) }
            .orEmpty()
            .associate { it.tsCode to it.close.toDouble() }
        return DailyMarketData(
            quotes = quotes,
            preClose = preClose,
            suspended = statuses.suspended,
            ipoFrozen = statuses.ipoFrozen,
            delisted = statuses.delisted,
        )
    }
}
