package org.shiroumi.backtest.domain

import kotlinx.datetime.LocalDate
import model.Candle
import model.PriceBasis

/**
 * 单个交易日的撮合上下文。
 *
 * 串联三类信息：
 *  - 市场快照：当日 [Candle] 与昨收 [preClose]（用于计算涨跌停区间）
 *  - 标的状态：是否停牌、是否处于 IPO 冻结期、是否退市
 *  - 账户只读视图：[ledger]（仅供 OrderSizer / 部分 Rule 读取，不可写）
 *
 * **强约束**：撮合阶段执行口径必须为 [PriceBasis.RAW]——
 * 复权后的价格不适合费用计算、涨跌停判定与权益核算。
 *
 * @param executionBasis 来自 [org.shiroumi.backtest.config.BacktestConfig.executionBasis]，
 *                       构造时强校验为 RAW（其它取值会抛出 IllegalArgumentException）
 */
data class MatchingContext(
    val tradeDate: LocalDate,
    val executionBasis: PriceBasis,
    val ledger: LedgerView,
    val quotes: Map<String, Candle>,
    val preClose: Map<String, Double>,
    val suspended: Set<String>,
    val ipoFrozen: Set<String>,
    val delisted: Set<String>,
    val signalLimitUp: Set<String> = emptySet(),
) {
    init {
        require(executionBasis == PriceBasis.RAW) {
            "撮合口径必须为 RAW（详见 docs/architecture/backtest-engine-design.md §2.10）"
        }
    }

    fun bar(tsCode: String): Candle? = quotes[tsCode]
    fun preCloseOf(tsCode: String): Double? = preClose[tsCode]
    fun isSuspended(tsCode: String): Boolean = tsCode in suspended
    fun isIpoFrozen(tsCode: String): Boolean = tsCode in ipoFrozen
    fun isDelisted(tsCode: String): Boolean = tsCode in delisted
    fun isSignalLimitUp(tsCode: String): Boolean = tsCode in signalLimitUp
}
