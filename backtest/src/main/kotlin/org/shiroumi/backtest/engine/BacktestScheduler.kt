package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.config.BacktestConfig
import org.shiroumi.backtest.config.MatchingPolicyKind
import org.shiroumi.backtest.domain.BlockedOrder
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.domain.MatchingContext
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.domain.ValidatedOrder
import org.shiroumi.backtest.feed.StrategyDecisionFeed
import org.shiroumi.backtest.ledger.AccountLedger
import org.shiroumi.backtest.ledger.OrderSizer
import org.shiroumi.backtest.ledger.SessionSettler
import org.shiroumi.backtest.match.ClosePriceMatching
import org.shiroumi.backtest.match.CostModel
import org.shiroumi.backtest.match.LimitOrderMatching
import org.shiroumi.backtest.match.MatchingEngine
import org.shiroumi.backtest.match.OpenPriceMatching
import org.shiroumi.backtest.match.SlippageModel
import org.shiroumi.backtest.match.UnfilledOrder
import org.shiroumi.backtest.match.VwapMatching
import org.shiroumi.backtest.output.TradeAudit
import org.shiroumi.backtest.rule.DefaultRuleChain
import org.shiroumi.backtest.rule.RuleValidator
import org.shiroumi.backtest.rule.ValidationResult

/**
 * 按交易日推进回测主循环。
 *
 * 每个交易日严格按 preOpen → fetchDecisions → size → validate → match → ledger.apply → postClose 执行。
 * 单日失败会被记录为 FAILED，调度器继续推进后续交易日。
 */
class BacktestScheduler(
    private val config: BacktestConfig,
    private val calendar: TradingCalendar,
    private val marketDataFeed: BacktestMarketDataFeed,
    private val decisionFeed: StrategyDecisionFeed,
    private val ledger: AccountLedger = AccountLedger(config.initialCapital),
    private val settler: SessionSettler = SessionSettler(ledger),
    private val orderSizer: OrderSizer = OrderSizer(config.costs),
    private val validator: RuleValidator = DefaultRuleChain.build(config),
    private val matchingEngine: MatchingEngine = MatchingEngine(
        policy = when (config.matching.policy) {
            MatchingPolicyKind.OPEN_PRICE -> OpenPriceMatching
            MatchingPolicyKind.VWAP -> VwapMatching
            MatchingPolicyKind.CLOSE_PRICE -> ClosePriceMatching
            MatchingPolicyKind.LIMIT -> LimitOrderMatching
        },
        slippage = SlippageModel(config.slippage),
        costs = CostModel(config.costs),
    ),
) {

    fun runLoop(
        start: LocalDate = config.startDate,
        end: LocalDate = config.endDate,
    ): SchedulerRunResult {
        val records = mutableListOf<DailyRunRecord>()
        for (date in calendar.tradingDays(start, end)) {
            records += runDay(date)
        }
        return SchedulerRunResult(records, ledger, config.initialCapital)
    }

    private fun runDay(date: LocalDate): DailyRunRecord {
        val unfilledBefore = matchingEngine.unfilledSnapshot().size
        return try {
            settler.preOpen(date)
            val market = marketDataFeed.marketDataFor(date)
            val ctx = MatchingContext(
                tradeDate = date,
                executionBasis = config.executionBasis,
                ledger = ledger,
                quotes = market.quotes,
                preClose = market.preClose,
                suspended = market.suspended,
                ipoFrozen = market.ipoFrozen,
                delisted = market.delisted,
            )
            val decisions = decisionFeed.decisionsFor(date)
            val sizing = orderSizer.size(decisions, ledger, ctx)
            val validated = mutableListOf<ValidatedOrder>()
            val blocked = sizing.blockedOrders.toMutableList()
            for (draft in sizing.draftOrders) {
                when (val result = validator.validate(draft, ctx)) {
                    is ValidationResult.Validated -> validated += result.order
                    is ValidationResult.Blocked -> blocked += result.blocked
                }
            }
            val fills = matchingEngine.match(validated, ctx)
            ledger.apply(fills)
            val settlement = settler.postClose(date, market.closePriceMap())
            DailyRunRecord(
                tradeDate = date,
                status = DailyRunStatus.COMPLETED,
                decisions = decisions,
                draftOrders = sizing.draftOrders,
                validatedOrders = validated,
                blockedOrders = blocked,
                audits = sizing.audits,
                fills = fills,
                unfilled = matchingEngine.unfilledSnapshot().drop(unfilledBefore),
                settlement = settlement,
            )
        } catch (error: Throwable) {
            DailyRunRecord(
                tradeDate = date,
                status = DailyRunStatus.FAILED,
                unfilled = matchingEngine.unfilledSnapshot().drop(unfilledBefore),
                error = error.message ?: error::class.simpleName ?: "unknown error",
            )
        }
    }
}

data class SchedulerRunResult(
    val days: List<DailyRunRecord>,
    val ledger: AccountLedger,
    val initialCapital: org.shiroumi.backtest.domain.Money,
)

data class DailyRunRecord(
    val tradeDate: LocalDate,
    val status: DailyRunStatus,
    val decisions: List<StrategyDecision> = emptyList(),
    val draftOrders: List<DraftOrder> = emptyList(),
    val validatedOrders: List<ValidatedOrder> = emptyList(),
    val blockedOrders: List<BlockedOrder> = emptyList(),
    val audits: List<TradeAudit> = emptyList(),
    val fills: List<Fill> = emptyList(),
    val unfilled: List<UnfilledOrder> = emptyList(),
    val settlement: org.shiroumi.backtest.ledger.DailySettlement? = null,
    val error: String? = null,
)

enum class DailyRunStatus { COMPLETED, FAILED }
