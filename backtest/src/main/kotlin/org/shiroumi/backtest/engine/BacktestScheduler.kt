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
 * 每个交易日严格按 preOpen → fetchDecisions → checkExits → size → validate → match
 * → ledger.apply → recordEntryMeta → postClose 执行。
 *
 * 当 [exitManager] 提供时，调度器会在盘前自动检查持仓退出条件（止盈/止损），
 * 并在成交后记录入场元数据供后续退出判定使用。
 *
 * 当 [entryGatekeeper] 提供时，调度器会在取出当日决策后、加入退出决策前，对入场候选应用入场闸门
 * （每日入场上限 + entryPriority 降序 + 已持有跳过 + 跳空过滤），与生产持仓状态机 advance 对齐。
 *
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
        slippage = SlippageModel(config.slippage),
        costs = CostModel(config.costs),
    ),
    /** 持仓退出管理器。为 null 时不启用自动退出管理（保持原有行为）。 */
    private val exitManager: PositionExitManager? = null,
    /** 入场闸门。为 null 时不应用入场上限/排序/跳空过滤（保持原有目标组合行为）。 */
    private val entryGatekeeper: EntryGatekeeper? = null,
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
            val rawDecisions = decisionFeed.decisionsFor(date)

            // 入场闸门：每日入场上限 + entryPriority 降序 + 已持有跳过 + 跳空过滤（对齐生产 advance）。
            // 在加入退出决策前应用，仅约束入场侧；存量持仓的离场完全交给 exitManager。
            val decisions = if (entryGatekeeper != null) {
                val heldTsCodes = ledger.positions
                    .filterValues { it.totalQty > 0L }
                    .keys
                entryGatekeeper.gate(
                    date = date,
                    decisions = rawDecisions,
                    market = marketDataFeed.marketDataFor(date),
                    heldTsCodes = heldTsCodes,
                ).toMutableList()
            } else {
                rawDecisions.toMutableList()
            }

            // 检查持仓退出条件（止盈 / 保盈阶梯 / 浅止损 / 时间止损 / 价格止损）
            val exitDecisions = exitManager?.checkExits(date, ledger.positions).orEmpty()
            if (exitDecisions.isNotEmpty()) {
                decisions.addAll(exitDecisions)
            }

            if (decisions.isEmpty() && ledger.positions.isEmpty()) {
                val settlement = settler.postClose(date, emptyMap())
                return DailyRunRecord(
                    tradeDate = date,
                    status = DailyRunStatus.COMPLETED,
                    settlement = settlement,
                )
            }
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
                signalLimitUp = market.signalLimitUp,
            )
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

            // 记录入场元数据 / 清除离场元数据（供退出管理器使用）
            if (exitManager != null) {
                for (fill in fills) {
                    when (fill.side) {
                        org.shiroumi.backtest.domain.Side.BUY -> {
                            exitManager.onEntry(fill.tsCode, date, fill.price)
                        }
                        org.shiroumi.backtest.domain.Side.SELL -> {
                            val remaining = ledger.totalQty(fill.tsCode)
                            if (remaining <= 0L) {
                                exitManager.onExit(fill.tsCode)
                            }
                        }
                    }
                }
            }

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
