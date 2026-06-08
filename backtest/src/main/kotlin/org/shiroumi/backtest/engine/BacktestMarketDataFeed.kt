package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import model.Candle

/**
 * 回测单日市场数据输入。
 *
 * M6 只定义调度器所需的最小事实；真实 DB/CLI 装配在 M8 集成入口继续落地。
 */
fun interface BacktestMarketDataFeed {
    fun marketDataFor(date: LocalDate): DailyMarketData
}

data class DailyMarketData(
    val quotes: Map<String, Candle>,
    val preClose: Map<String, Double> = emptyMap(),
    val suspended: Set<String> = emptySet(),
    val ipoFrozen: Set<String> = emptySet(),
    val delisted: Set<String> = emptySet(),
    val signalLimitUp: Set<String> = emptySet(),
) {
    fun closePriceMap(): Map<String, Double> = quotes.mapValues { (_, candle) -> candle.close.toDouble() }
}
