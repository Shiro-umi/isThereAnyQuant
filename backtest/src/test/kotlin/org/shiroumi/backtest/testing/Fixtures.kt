@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package org.shiroumi.backtest.testing

import kotlinx.datetime.LocalDate
import model.Candle
import model.PriceBasis
import org.shiroumi.backtest.config.CostModelConfig
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.LedgerView
import org.shiroumi.backtest.domain.Lot
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StockPosition
import org.shiroumi.backtest.domain.ValidatedOrder

/** 测试用的常用日期，避免在每个用例里到处写 LocalDate(...)。 */
internal val T0: LocalDate = LocalDate(2024, 1, 2)
internal val T1: LocalDate = LocalDate(2024, 1, 3)

/** 最小化构造 Candle，只关心 open/high/low/close/volume 几个字段。 */
internal fun candle(
    tsCode: String = "000001.SZ",
    date: LocalDate = T0,
    open: Double = 10.0,
    high: Double = 10.5,
    low: Double = 9.8,
    close: Double = 10.2,
    volume: Double = 1_000_000.0,
): Candle = Candle(
    tsCode = tsCode,
    date = date,
    open = open.toFloat(),
    high = high.toFloat(),
    low = low.toFloat(),
    close = close.toFloat(),
    adj = 1.0f,
    volume = volume.toFloat(),
    turnoverReal = (close * volume).toFloat(),
    pe = 0f, peTtm = 0f, pb = 0f, ps = 0f, psTtm = 0f, mvTotal = 0f, mvCirc = 0f,
)

/** 简单的 LedgerView 测试桩。 */
internal class FakeLedger(
    override val cash: Money = Money.ofYuan(1_000_000),
    initialPositions: Map<String, StockPosition> = emptyMap(),
) : LedgerView {
    override val positions: Map<String, StockPosition> = initialPositions
    override fun equity(quote: Map<String, Double>): Money {
        var total = cash
        for ((tsCode, position) in positions) {
            val price = quote[tsCode] ?: continue
            total += Money.ofTrade(price, position.totalQty)
        }
        return total
    }
}

internal fun position(tsCode: String, qty: Long, cost: Double = 10.0, settled: Boolean = true): StockPosition =
    StockPosition(tsCode, Lot(buyDate = T0, quantity = qty, cost = cost, settled = settled))

internal fun ctx(
    tradeDate: LocalDate = T1,
    quotes: Map<String, Candle> = emptyMap(),
    preClose: Map<String, Double> = emptyMap(),
    suspended: Set<String> = emptySet(),
    ipoFrozen: Set<String> = emptySet(),
    delisted: Set<String> = emptySet(),
    ledger: LedgerView = FakeLedger(),
    signalLimitUp: Set<String> = emptySet(),
): MatchingContext = MatchingContext(
    tradeDate = tradeDate,
    executionBasis = PriceBasis.RAW,
    ledger = ledger,
    quotes = quotes,
    preClose = preClose,
    suspended = suspended,
    ipoFrozen = ipoFrozen,
    delisted = delisted,
    signalLimitUp = signalLimitUp,
)

internal fun order(
    tsCode: String = "000001.SZ",
    side: Side = Side.BUY,
    quantity: Long = 1_000L,
    limitPrice: Double? = null,
    hint: ExecutionHint = ExecutionHint.OPEN,
    orderId: String = "test-order",
    reason: String = "test",
): DraftOrder = DraftOrder(
    orderId = orderId,
    effectiveDate = T1,
    tsCode = tsCode,
    side = side,
    quantity = quantity,
    limitPrice = limitPrice,
    hint = hint,
    reason = reason,
)

internal fun validated(
    tsCode: String = "000001.SZ",
    side: Side = Side.BUY,
    quantity: Long = 1_000L,
    limitPrice: Double = 10.0,
    hint: ExecutionHint = ExecutionHint.OPEN,
    orderId: String = "validated-order",
): ValidatedOrder = ValidatedOrder(
    orderId = orderId,
    effectiveDate = T1,
    tsCode = tsCode,
    side = side,
    quantity = quantity,
    limitPrice = limitPrice,
    hint = hint,
)

internal val defaultCosts: CostModelConfig = CostModelConfig()
