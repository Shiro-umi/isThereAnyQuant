package model.dataprovider

import kotlinx.serialization.Serializable
import model.candle.CandlePeriod

/**
 * K 线 Provider 的主键。
 *
 * 一个 `(tsCode, period)` 对应一个独立的 K 线快照空间：
 * - 历史窗口 H
 * - 实时窗口 R
 * - 合并视图 merged
 *
 * 这里不再把寻址抽象成通用 address，而是直接使用业务强类型键。
 */
@Serializable
data class CandleKey(
    val tsCode: String,
    val period: CandlePeriod
) : DataProviderKey {
    /**
     * `id` 仅作为缓存与注册中心内部索引使用。
     * 字符串结构保持稳定，便于后续日志与调试定位。
     */
    override val id: String = "candle:$tsCode:${period.name}"
}

/**
 * 市场情绪 Provider 的主键。
 *
 * `scope` 用来区分不同情绪计算范围，例如：
 * - 主板
 * - 全市场
 * - 某个策略股票池
 */
@Serializable
data class SentimentKey(
    val scope: String
) : DataProviderKey {
    override val id: String = "sentiment:$scope"
}

/**
 * 盘中因子 Provider 的主键。
 *
 * 因子计算通常既受范围影响，也受交易日影响，
 * 因此这里同时显式建模 `scope` 和 `tradeDate`。
 */
@Serializable
data class IntradayFactorKey(
    val scope: String,
    val tradeDate: String
) : DataProviderKey {
    override val id: String = "intraday-factor:$scope:$tradeDate"
}
