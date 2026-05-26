package org.shiroumi.backtest.ledger

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.output.DailyPositionSnapshot
import org.shiroumi.backtest.output.EquityPoint

/**
 * 交易日结算器。
 *
 * 盘前释放昨日买入的 T+1 锁仓；收盘后按 RAW 收盘价生成权益点与持仓快照，
 * 并触发公司行动处理入口。
 */
class SessionSettler(
    private val ledger: AccountLedger,
    private val corporateActionApplier: CorporateActionApplier = NoopCorporateActionApplier,
) {

    private val positionSnapshots: MutableList<DailyPositionSnapshot> = mutableListOf()
    private val equityCurve: MutableList<EquityPoint> = mutableListOf()

    fun positionSnapshotHistory(): List<DailyPositionSnapshot> = positionSnapshots.toList()

    fun equityCurveSnapshot(): List<EquityPoint> = equityCurve.toList()

    fun preOpen(date: LocalDate) {
        ledger.positionLedger.settleDay(date)
    }

    fun postClose(date: LocalDate, quote: Map<String, Double>): DailySettlement {
        val positionValue = ledger.positionValue(quote)
        val equity = ledger.cash + positionValue
        val positionSnapshot = DailyPositionSnapshot(
            tradeDate = date,
            cash = ledger.cash,
            equity = equity,
            positions = ledger.positions.values.toList(),
        )
        val equityPoint = EquityPoint(
            tradeDate = date,
            cash = ledger.cash,
            positionValue = positionValue,
            equity = equity,
        )
        positionSnapshots += positionSnapshot
        equityCurve += equityPoint
        corporateActionApplier.applyAfterClose(date, ledger)
        return DailySettlement(positionSnapshot, equityPoint)
    }
}

fun interface CorporateActionApplier {
    fun applyAfterClose(date: LocalDate, ledger: AccountLedger)
}

object NoopCorporateActionApplier : CorporateActionApplier {
    override fun applyAfterClose(date: LocalDate, ledger: AccountLedger) = Unit
}

class InMemoryCorporateActionApplier(
    actions: List<CorporateAction>,
) : CorporateActionApplier {
    private val byDate = actions.groupBy { it.exDate }

    override fun applyAfterClose(date: LocalDate, ledger: AccountLedger) {
        for (action in byDate[date].orEmpty()) {
            val qty = ledger.totalQty(action.tsCode)
            if (qty <= 0L) continue
            if (action.cashDividendPerShare > 0.0) {
                ledger.applyDividend(
                    tradeDate = date,
                    tsCode = action.tsCode,
                    amount = org.shiroumi.backtest.domain.Money.ofYuan(action.cashDividendPerShare * qty),
                )
            }
            if (action.shareMultiplier != 1.0) {
                ledger.positionLedger.applyShareMultiplier(action.tsCode, action.shareMultiplier)
            }
        }
    }
}

data class CorporateAction(
    val exDate: LocalDate,
    val tsCode: String,
    val cashDividendPerShare: Double = 0.0,
    val shareMultiplier: Double = 1.0,
) {
    init {
        require(cashDividendPerShare >= 0.0) { "cash dividend cannot be negative" }
        require(shareMultiplier > 0.0) { "share multiplier must be positive" }
    }
}

data class DailySettlement(
    val positionSnapshot: DailyPositionSnapshot,
    val equityPoint: EquityPoint,
)
