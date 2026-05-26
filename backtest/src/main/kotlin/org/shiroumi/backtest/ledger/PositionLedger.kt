package org.shiroumi.backtest.ledger

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.domain.Lot
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StockPosition

/**
 * 持仓总账：按标的代码维护 [StockPosition]。
 *
 * **核心不变量（1 买 1 卖语义，对齐设计文档 §2.6）**：
 *  - 任意 tsCode 在 holdings 中要么不存在，要么对应的 StockPosition.lot **唯一存在**；
 *  - [applyBuy] 在已存在 lot 时立即抛 [IllegalStateException]，作为最后一道防御性守护
 *    （正常路径下 [OrderSizer] 在更上游就用 ALREADY_HOLDING 阻断）；
 *  - [applySell] 必须以"全量清仓"模式调用，`fill.quantity != lot.quantity` 时抛错。
 *
 * T+1 锁仓：买入时 settled=false，[settleDay] 在收盘后把 buyDate < settleDate 的 Lot 标 settled=true。
 */
class PositionLedger {

    private val holdings: MutableMap<String, StockPosition> = mutableMapOf()

    fun snapshot(): Map<String, StockPosition> = holdings.toMap()

    fun get(tsCode: String): StockPosition? = holdings[tsCode]

    fun availableQty(tsCode: String): Long = holdings[tsCode]?.availableQty ?: 0L

    fun lockedTodayQty(tsCode: String): Long = holdings[tsCode]?.lockedTodayQty ?: 0L

    fun totalQty(tsCode: String): Long = holdings[tsCode]?.totalQty ?: 0L

    fun avgCost(tsCode: String): Double = holdings[tsCode]?.avgCost ?: 0.0

    fun applyShareMultiplier(tsCode: String, multiplier: Double) {
        require(multiplier > 0.0) { "share multiplier must be positive" }
        val position = holdings[tsCode] ?: return
        val lot = position.lot ?: return
        val adjustedQty = kotlin.math.round(lot.quantity * multiplier).toLong()
        if (adjustedQty <= 0L) {
            holdings.remove(tsCode)
            return
        }
        holdings[tsCode] = position.copy(
            lot = lot.copy(
                quantity = adjustedQty,
                cost = lot.cost / multiplier,
            )
        )
    }

    /**
     * 应用一次买入成交：新建唯一 Lot。
     *
     * 防御性守护：调用前应由 [OrderSizer] 通过 `ALREADY_HOLDING` 把已有持仓的买单拦掉；
     * 万一调用了此处而 lot 已存在，立刻抛错，避免悄无声息地破坏单 Lot 不变量。
     */
    fun applyBuy(fill: Fill) {
        require(fill.side == Side.BUY) { "applyBuy 只接受 BUY 方向的 Fill" }
        val existing = holdings[fill.tsCode]
        check(existing == null || existing.lot == null) {
            "${fill.tsCode} 已有未平仓 Lot，违反 1 买 1 卖单 Lot 不变量"
        }
        holdings[fill.tsCode] = StockPosition(
            tsCode = fill.tsCode,
            lot = Lot(buyDate = fill.tradeDate, quantity = fill.quantity, cost = fill.price, settled = false),
        )
    }

    /**
     * 应用一次卖出成交：必须全量清仓。
     *
     * `fill.quantity` 必须等于持仓 lot.quantity；否则抛错以暴露上游缺陷。
     */
    fun applySell(fill: Fill) {
        require(fill.side == Side.SELL) { "applySell 只接受 SELL 方向的 Fill" }
        val position = holdings[fill.tsCode]
            ?: error("${fill.tsCode} 无持仓，却收到 SELL Fill")
        val lot = position.lot ?: error("${fill.tsCode} lot 已为空，却收到 SELL Fill")
        check(fill.quantity == lot.quantity) {
            "${fill.tsCode} SELL 必须全量清仓，期望 ${lot.quantity} 股，实际 ${fill.quantity} 股"
        }
        // 一次性清掉 Lot，并把空仓位从 map 中移除，保持快照精简
        holdings.remove(fill.tsCode)
    }

    /**
     * 收盘后结算：把 buyDate < settleDate 的 Lot 标 settled=true，
     * 实现 T+1 解锁——T 日买入的 Lot 在 T+1 日 preOpen 时变为可卖。
     */
    fun settleDay(settleDate: LocalDate) {
        for ((tsCode, position) in holdings.toMap()) {
            val lot = position.lot ?: continue
            if (!lot.settled && lot.buyDate < settleDate) {
                holdings[tsCode] = position.copy(lot = lot.copy(settled = true))
            }
        }
    }
}
