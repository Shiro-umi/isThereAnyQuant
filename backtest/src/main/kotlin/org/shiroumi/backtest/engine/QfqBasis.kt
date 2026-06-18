package org.shiroumi.backtest.engine

import model.Candle

/**
 * 回测执行行情的价格坐标系收敛工具。
 *
 * 回测执行口径固定 QFQ（前复权），与上游 agent 买点价、asof 历史取数路由（InternalCliAsofRoute）
 * 同一价格坐标系：agent 在 QFQ K 线上推演买点限价，撮合（OpenPriceMatching / LimitOrderMatching 等）
 * 读 Candle.open/low/high/close 判 `low<=limit`，因此这些字段必须是 QFQ。
 *
 * 数据库 `stock_daily_data` 的 low/open/high/close 列存的是「以最新日为基准的前复权价」（随送转动态变化），
 * 而 *_qfq 列才是「以该 K 线自身周期为基准的前复权价」，与 agent 通过 asof 路由看到的口径一致
 * （asof 路由：`if (lowQfq>0) lowQfq else low`）。送转标的两者差异巨大（如 301396.SZ 04-02：
 * low=166.66 vs low_qfq=118.95），若撮合用 low 列会让限价系统性永不触发——这正是漏单根因。
 *
 * 因此回测加载蜡烛后必须统一经此转换：QFQ 字段（>0）覆盖 OHLC，否则回退原值，与 asof 路由完全同口径。
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun Candle.toQfqBasis(): Candle = copy(
    open = if (openQfq > 0f) openQfq else open,
    high = if (highQfq > 0f) highQfq else high,
    low = if (lowQfq > 0f) lowQfq else low,
    close = if (closeQfq > 0f) closeQfq else close,
)

/** 对一组蜡烛批量收敛到 QFQ 坐标系。 */
fun List<Candle>.toQfqBasis(): List<Candle> = map { it.toQfqBasis() }
