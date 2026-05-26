package org.shiroumi.backtest.rule

import org.shiroumi.backtest.config.CostModelConfig
import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.ExecutionHint
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.RuleOutcome
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.ledger.OrderSizer
import org.shiroumi.backtest.testing.FakeLedger
import org.shiroumi.backtest.testing.T1
import org.shiroumi.backtest.testing.candle
import org.shiroumi.backtest.testing.ctx
import org.shiroumi.backtest.testing.order
import org.shiroumi.backtest.testing.position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 校验 [BlockReason] 的 11 个业务阻断原因都有最小失败用例覆盖。 */
class BlockReasonCoverageTest {
    private val ruleReasons: Set<BlockReason> = setOf(
        BlockReason.SUSPENDED,
        BlockReason.DELISTED,
        BlockReason.IPO_FROZEN,
        BlockReason.LIMIT_UP_BUY,
        BlockReason.LIMIT_DOWN_SELL,
        BlockReason.INSUFFICIENT_CASH,
        BlockReason.INSUFFICIENT_QUANTITY_T1,
        BlockReason.BELOW_LOT_SIZE,
        BlockReason.LIQUIDITY_EXHAUSTED,
    )

    @Test fun `M2 规则链覆盖 9 种 BlockReason`() {
        val hit = mutableSetOf<BlockReason>()

        // SUSPENDED
        runRule(TradabilityRule(), order(tsCode = "000001.SZ"), ctx(suspended = setOf("000001.SZ"))).let { hit.recordBlock(it) }
        // DELISTED
        runRule(TradabilityRule(), order(tsCode = "000001.SZ"), ctx(delisted = setOf("000001.SZ"))).let { hit.recordBlock(it) }
        // IPO_FROZEN
        val ipoTs = "688001.SH"
        runRule(
            TradabilityRule(),
            order(tsCode = ipoTs),
            ctx(ipoFrozen = setOf(ipoTs), quotes = mapOf(ipoTs to candle(ipoTs))),
        ).let { hit.recordBlock(it) }
        // LIMIT_UP_BUY
        val luTs = "600000.SH"
        runRule(
            PriceLimitRule(),
            order(tsCode = luTs, side = Side.BUY, limitPrice = 11.00),
            ctx(preClose = mapOf(luTs to 10.00), quotes = mapOf(luTs to candle(luTs, open = 11.00))),
        ).let { hit.recordBlock(it) }
        // LIMIT_DOWN_SELL
        val ldTs = "600000.SH"
        runRule(
            PriceLimitRule(),
            order(tsCode = ldTs, side = Side.SELL, limitPrice = 9.00),
            ctx(preClose = mapOf(ldTs to 10.00), quotes = mapOf(ldTs to candle(ldTs, open = 9.00))),
        ).let { hit.recordBlock(it) }
        // BELOW_LOT_SIZE
        runRule(LotSizeRule(), order(side = Side.BUY, quantity = 50L), ctx()).let { hit.recordBlock(it) }
        // INSUFFICIENT_QUANTITY_T1
        runRule(
            T1SettlementRule(),
            order(tsCode = "000001.SZ", side = Side.SELL, quantity = 100L),
            ctx(),
        ).let { hit.recordBlock(it) }
        // INSUFFICIENT_CASH
        runRule(
            CashAvailabilityRule(CostModelConfig()),
            order(side = Side.BUY, quantity = 10_000L, limitPrice = 10.0),
            ctx(ledger = FakeLedger(cash = Money.ofYuan(10))),
        ).let { hit.recordBlock(it) }
        // LIQUIDITY_EXHAUSTED
        val liqTs = "000001.SZ"
        runRule(
            LiquidityRule(0.01),
            order(tsCode = liqTs, side = Side.SELL, quantity = 1_000_000L),
            ctx(
                quotes = mapOf(liqTs to candle(liqTs, volume = 1_000_000.0)),
                ledger = FakeLedger(initialPositions = mapOf(liqTs to position(liqTs, 1_000_000L, settled = true))),
            ),
        ).let { hit.recordBlock(it) }

        assertEquals(ruleReasons, hit)
    }

    private fun runRule(rule: MarketRule, draft: org.shiroumi.backtest.domain.DraftOrder, c: org.shiroumi.backtest.domain.MatchingContext): RuleOutcome {
        val out = rule.apply(draft, c)
        assertTrue(out is RuleOutcome.Block, "期望阻断但得到 $out（rule=${rule::class.simpleName}）")
        return out
    }

    private fun MutableSet<BlockReason>.recordBlock(outcome: RuleOutcome) {
        require(outcome is RuleOutcome.Block)
        add(outcome.reason)
    }

    @Test fun `M3 OrderSizer 覆盖 ALREADY_HOLDING 与 SAME_DAY_REVERSE`() {
        val hit = mutableSetOf<BlockReason>()
        val sizer = OrderSizer()
        val ts = "000001.SZ"

        sizer.size(
            decisions = listOf(
                StrategyDecision.TradeIntentDecision(
                    effectiveDate = T1,
                    reason = "explicit buy",
                    tsCode = ts,
                    side = Side.BUY,
                    weight = 0.2,
                    hint = ExecutionHint.OPEN,
                ),
            ),
            ledger = FakeLedger(initialPositions = mapOf(ts to position(ts, 100L))),
            ctx = ctx(quotes = mapOf(ts to candle(ts))),
        ).blockedOrders.forEach { hit += it.reason }

        sizer.size(
            decisions = listOf(
                StrategyDecision.TargetPortfolioDecision(
                    effectiveDate = T1,
                    reason = "target",
                    targetWeights = mapOf(ts to 0.2),
                    sentimentExposure = 0.2,
                ),
                StrategyDecision.TradeIntentDecision(
                    effectiveDate = T1,
                    reason = "sell",
                    tsCode = ts,
                    side = Side.SELL,
                ),
            ),
            ledger = FakeLedger(cash = Money.ofYuan(1_000_000)),
            ctx = ctx(quotes = mapOf(ts to candle(ts))),
        ).blockedOrders.forEach { hit += it.reason }

        assertEquals(setOf(BlockReason.ALREADY_HOLDING, BlockReason.SAME_DAY_REVERSE), hit)
    }
}
