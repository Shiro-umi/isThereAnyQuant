package org.shiroumi.backtest.ledger

import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.domain.LedgerView
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StockPosition
import org.shiroumi.backtest.output.CashFlow
import org.shiroumi.backtest.output.CashFlowTag

/**
 * 账户总账：现金账户 + 持仓账户的唯一写入口。
 *
 * 策略层永远拿不到本对象；OrderSizer / RuleValidator 只能通过 [LedgerView] 读取快照。
 * 真实账户变更只允许经由撮合成交 [apply] 进入，避免策略把现金或持仓状态当成输入。
 */
class AccountLedger(
    initialCash: Money,
    val positionLedger: PositionLedger = PositionLedger(),
) : LedgerView {

    override var cash: Money = initialCash
        private set

    override val positions: Map<String, StockPosition>
        get() = positionLedger.snapshot()

    private val appliedFills: MutableList<Fill> = mutableListOf()
    private val cashFlows: MutableList<CashFlow> = mutableListOf()

    fun appliedFillSnapshot(): List<Fill> = appliedFills.toList()

    fun cashFlowSnapshot(): List<CashFlow> = cashFlows.toList()

    fun applyDividend(tradeDate: kotlinx.datetime.LocalDate, tsCode: String, amount: Money) {
        if (amount.isZero) return
        cash += amount
        cashFlows += CashFlow(tradeDate, amount, CashFlowTag.DIVIDEND, tsCode)
    }

    fun apply(fills: List<Fill>) {
        for (fill in fills) apply(fill)
    }

    fun apply(fill: Fill) {
        when (fill.side) {
            Side.BUY -> positionLedger.applyBuy(fill)
            Side.SELL -> positionLedger.applySell(fill)
        }
        cash += fill.netCashFlow
        appliedFills += fill
        appendCashFlows(fill)
    }

    override fun equity(quote: Map<String, Double>): Money = cash + positionValue(quote)

    fun positionValue(quote: Map<String, Double>): Money {
        var total = Money.ZERO
        for ((tsCode, position) in positions) {
            val price = quote[tsCode] ?: continue
            total += Money.ofTrade(price, position.totalQty)
        }
        return total
    }

    private fun appendCashFlows(fill: Fill) {
        val grossTag = when (fill.side) {
            Side.BUY -> CashFlowTag.BUY
            Side.SELL -> CashFlowTag.SELL
        }
        val grossAmount = when (fill.side) {
            Side.BUY -> -fill.grossAmount
            Side.SELL -> fill.grossAmount
        }
        cashFlows += CashFlow(fill.tradeDate, grossAmount, grossTag, "${fill.tsCode} ${fill.quantity} @ ${fill.price}")
        if (!fill.commission.isZero) {
            cashFlows += CashFlow(fill.tradeDate, -fill.commission, CashFlowTag.COMMISSION, fill.tsCode)
        }
        if (!fill.transferFee.isZero) {
            cashFlows += CashFlow(fill.tradeDate, -fill.transferFee, CashFlowTag.TRANSFER_FEE, fill.tsCode)
        }
        if (!fill.stampDuty.isZero) {
            cashFlows += CashFlow(fill.tradeDate, -fill.stampDuty, CashFlowTag.STAMP_DUTY, fill.tsCode)
        }
    }
}
