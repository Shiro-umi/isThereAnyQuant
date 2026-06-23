package org.shiroumi.strategy.service.runtime

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import model.Candle
import model.candle.StrategyPositionTrackingDay
import model.candle.StrategyPositionTrackingResponse
import model.candle.StrategyTrackingEdge
import model.candle.StrategyTrackingEdgeKind
import model.candle.StrategyTrackingExitReason
import model.candle.StrategyTrackingNextExit
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode
import model.ws.PositionSource
import model.ws.StrategyPositionSnapshot
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockReader
import org.shiroumi.database.strategy.daily.repository.DailyHoldingState
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyHoldingRepository
import org.shiroumi.database.strategy.daily.repository.ProfitPredictionSelection
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.core.audit.StrategyAuditSummary
import org.shiroumi.strategy.service.postmarket.BreakdownRerankService
import org.shiroumi.strategy.service.postmarket.HoldingStateMachine
import utils.logger

private const val DEFAULT_TRACKING_LIMIT = 60

// 6 = 每日入场 3 只 × H3（持仓存活 daysSinceEntry 0/1，第 2 日离场）→ 同一交易日并发在手最多 6 只。
// 必须与前端 TrackingSlotCount 同步，否则后端发 6 只前端仍裁 5 只。
private const val MAX_TRACKING_SLOT_COUNT = 6

/**
 * 持仓跟踪时间线 runtime——`STRATEGY_POSITION_TRACKING` 快照的唯一 owner。
 *
 * 时间线逐日均为已确认交易日（由盘后确认链路构建），盘中投影不覆盖/追加实时日。
 * 展示链路的全部业务计算在此完成，前端只负责渲染：
 * - 节点盈亏：持有/清仓节点的成本（入场日开盘）、现价（观察日收盘）、
 *   浮动收益与持有期最高收益；选股节点的观察日价格与当日涨跌幅。
 * - 离场判决重建：清仓节点通过 [HoldingStateMachine.evaluateExit] 按生产持仓规则
 *   （[HoldingStateMachine.ExitRules.fromSystemProperties]，与盘后推进同一装配入口）
 *   重建离场原因与规则口径已实现收益（止盈/保盈按触价含高开穿越、到期按收盘）。
 * - 跨日流转边：持有主干 / 选股→次日买入 / 持有→清仓三类边及各自盈亏百分比
 *   （持有边=目标日当日涨跌、买入边=入场日开盘→收盘、卖出边=规则口径已实现收益）。
 *
 * 行情口径：统一前复权（QFQ），全部经 [Candle.getOpen] 系访问器（默认 useAdjusted=true，优先取 *_qfq 列，
 * *_qfq=0 缺失时回退原始列）。与生产推进 advanceHoldings（findByTradeDate 原样 Candle，getLow 取 lowQfq）、
 * agent 买点 limitPrice 同口径——三方都读 DB 同一份 *_qfq 列（同源、同锚），入场 LIMIT 触达/跳空过滤/离场判决与生产持仓完全一致。
 * 价格止损（生产默认关闭）的判决重建不还原信号日最低价，无法命中 PRICE_STOP 的清仓节点按收盘口径回退展示且不带原因标签。
 */
class StrategyPositionTrackingRuntime(
    private val snapshotHub: LocalStrategySnapshotHub<JsonElement>,
    private val json: Json,
    private val dataSource: StrategyPositionTrackingDataSource = DefaultStrategyPositionTrackingDataSource,
    private val trackingLimit: Int = DEFAULT_TRACKING_LIMIT,
    exitRules: HoldingStateMachine.ExitRules = HoldingStateMachine.ExitRules.fromSystemProperties(),
) {
    private val logger by logger("StrategyPositionTrackingRuntime")
    private val holdingStateMachine = HoldingStateMachine(exitRules)

    /**
     * 持仓跟踪时间线只由盘后确认链路（[PositionSource.DAILY_AUDIT_COMPLETE]）构建，逐日均为确认交易日。
     * 盘中投影不再覆盖/追加实时日：[PositionSource.INTRADAY_REALTIME] 与 [PositionSource.HISTORICAL_AUDIT]
     * 不发布跟踪快照。
     */
    suspend fun publishFromPositions(
        positionSnapshot: StrategyPositionSnapshot,
    ): StrategySnapshotEnvelope<JsonElement>? {
        val tracking = when (positionSnapshot.source) {
            PositionSource.HISTORICAL_AUDIT, PositionSource.INTRADAY_REALTIME -> null
            PositionSource.DAILY_AUDIT_COMPLETE -> buildHistoricalSnapshot()
        } ?: return null

        val envelope = snapshotHub.publish(
            StrategyTopic.POSITION_TRACKING,
            json.encodeToJsonElement(StrategyPositionTrackingResponse.serializer(), tracking)
        )
        logger.info(
            "strategy-service position tracking published source=${positionSnapshot.source} " +
                "tradeDate=${positionSnapshot.tradeDate} days=${tracking.days.size} " +
                "edges=${tracking.edges.size} version=${envelope.version}"
        )
        return envelope
    }

    private fun buildHistoricalSnapshot(): StrategyPositionTrackingResponse? {
        val records = dataSource.loadAuditSummaries(trackingLimit).reversed()
        if (records.isEmpty()) return null

        val tradeDates = records.map { it.tradeDate }
        val days = buildDays(
            records = records,
            selectionsByTradeDate = dataSource.loadSelectionsByTradeDate(tradeDates),
            holdingsByTradeDate = dataSource.loadHoldingsByTradeDate(tradeDates),
        )
        return enrich(days)
    }

    /**
     * 最早跟随日校准：以 [followStartDate] 为第一笔跟随买入日、空仓起步重放生产持仓规则，
     * 生成跟随者视角的持仓跟踪流（命令通道按需计算，不发布到 snapshot hub）。
     *
     * 模型自身持仓流中「已持有不重复入场」与每日入场上限都以模型书本判定；中途开始跟随的
     * 用户书本为空，模型因已有持仓而当日无新入场的日子，用户应照常买入。校准重放使用同一
     * [HoldingStateMachine]、同一入场候选来源（前一交易日选股 target_date=当日的 selected 票，
     * 行自带信号日）、同一入场优先级与跳空过滤，仅把书本起点换成 followStartDate 空仓逐日推进。
     *
     * 行情口径与生产推进一致：推进判定、入场 LIMIT 触达与跳空过滤均走前复权（QFQ）访问器（[barOf] 返回原始 DB Candle，
     * getXxx 默认取 *_qfq 列），与 advanceHoldings 同源同锚；展示盈亏由 [enrich] 同口径计算。重放仅覆盖已确认交易日，不含盘中实时投影。
     *
     * @return null = 无审计窗口或 followStartDate 不在窗口交易日内。
     */
    fun buildCalibratedSnapshot(followStartDate: LocalDate): StrategyPositionTrackingResponse? {
        val records = dataSource.loadAuditSummaries(trackingLimit).reversed()
        if (records.isEmpty()) return null
        val tradeDates = records.map { it.tradeDate }
        if (followStartDate !in tradeDates) return null

        // 候选行自带信号日（selection.tradeDate），行情窗口起点向前扩到最早信号日
        val candidatesByDate = tradeDates
            .filter { it >= followStartDate }
            .associateWith { date -> dataSource.loadSelectionsByTargetDate(date) }
        val candidateCodes = candidatesByDate.values.flatten().map { it.tsCode }.distinct()
        val replayStart = candidatesByDate.values.flatten()
            .minOfOrNull { it.tradeDate }
            ?.let { minOf(it, followStartDate) }
            ?: followStartDate
        val replayCandles = dataSource.loadCandles(candidateCodes, replayStart, tradeDates.last())
        // 行情口径统一前复权（QFQ）：取价访问器 getOpen/getLow/getHigh/getPrice 默认 useAdjusted=true，
        // 与生产推进 advanceHoldings（findByTradeDate 原样 Candle）、agent 买点 limitPrice 同口径——
        // 三方都读 DB low_qfq 等列（同源、同锚），LIMIT 触达/跳空过滤/离场判决与生产持仓完全一致。
        fun barOf(tsCode: String, date: LocalDate): Candle? =
            replayCandles[tsCode]?.get(date)

        var book = emptyList<DailyHoldingState>()
        val replayedHoldings = mutableMapOf<LocalDate, List<DailyHoldingState>>()
        for (date in tradeDates) {
            if (date < followStartDate) {
                replayedHoldings[date] = emptyList()
                continue
            }
            val candidates = candidatesByDate[date].orEmpty()
            // 破位加分：与生产推进 PostMarketPreparationJob 同源注入同一判定——同一开关、同一信号日 T 锚
            // （selection.tradeDate = previousTradeDate）、同一连续有效收盘序列。否则跟踪页重放与生产持仓分叉。
            // 同一 date 下所有候选 tradeDate 一致（= 信号日 T），取首条 tradeDate 作锚；空候选不取窗。
            val breakdownFlags: Map<String, Boolean> =
                if (holdingStateMachine.entryCapEnabled && candidates.isNotEmpty()) {
                    BreakdownRerankService.evaluate(
                        candidates.map { it.tsCode },
                        candidates.first().tradeDate,
                    )
                } else {
                    emptyMap()
                }
            val newEntries = candidates.map { selection ->
                val signalBar = barOf(selection.tsCode, selection.tradeDate)
                HoldingStateMachine.EntryCandidate(
                    tsCode = selection.tsCode,
                    signalDateLow = signalBar?.getLow()?.toDouble() ?: 0.0,
                    signalDateClose = signalBar?.getPrice()?.toDouble() ?: 0.0,
                    // 入场优先级 = 模型分（与生产推进 PostMarketPreparationJob 同口径：模型分降序取前 maxDailyEntries 只）。
                    entryPriority = if (holdingStateMachine.entryCapEnabled) selection.modelScore else 0.0,
                    breakdownFlag = breakdownFlags[selection.tsCode] ?: false,
                    // Agent 买点：与生产推进同源注入 selection.limitPrice，否则跟踪页 LIMIT 入场口径与生产持仓分叉。
                    entryLimitPrice = selection.limitPrice,
                )
            }
            val result = holdingStateMachine.advance(
                tradeDate = date,
                previousHoldings = book,
                newEntries = newEntries,
                tradingDaysSince = dataSource::tradingDaysSince,
                candleFor = ::barOf,
            )
            book = result.holdings
            replayedHoldings[date] = result.holdings
        }

        val days = buildDays(
            records = records,
            selectionsByTradeDate = dataSource.loadSelectionsByTradeDate(tradeDates),
            holdingsByTradeDate = replayedHoldings,
        )
        return enrich(days)
            .copy(followStartDate = followStartDate.toString())
    }

    /** 由审计窗口 + 选股 + 持仓快照构建逐日三列节点（选股/持仓/清仓），模型流与校准流共用。 */
    private fun buildDays(
        records: List<StrategyAuditSummary>,
        selectionsByTradeDate: Map<LocalDate, List<ProfitPredictionSelection>>,
        holdingsByTradeDate: Map<LocalDate, List<DailyHoldingState>>,
    ): List<StrategyPositionTrackingDay> {
        val allCodes = records.flatMap { summary ->
            val selections = selectionsByTradeDate[summary.tradeDate].orEmpty().map { it.tsCode }
            val holdings = holdingsByTradeDate[summary.tradeDate].orEmpty().map { it.tsCode }
            selections + holdings
        }.distinct()
        val stockNames = dataSource.loadStockNames(allCodes)

        return records.mapIndexed { index, summary ->
            val currentHoldings = holdingsByTradeDate[summary.tradeDate].orEmpty()
            val currentPositionCodes = currentHoldings.mapTo(mutableSetOf()) { it.tsCode }
            // 选股列：当日选出（target_date=次日）的 selected 票
            val selections = selectionsByTradeDate[summary.tradeDate]
                .orEmpty()
                .take(MAX_TRACKING_SLOT_COUNT)
                .mapIndexed { idx, selection ->
                    trackingNode(
                        code = selection.tsCode,
                        stockNames = stockNames,
                        section = StrategyTrackingSection.SELECTION,
                        slotIndex = idx,
                        modelScore = selection.modelScore,
                        entryHint = selection.limitPrice
                    )
                }
            // 持仓列：当日仍在持有的票，buyDate 取状态机 entryDate（用于跨多日连线追溯）
            val holdings = currentHoldings
                .take(MAX_TRACKING_SLOT_COUNT)
                .mapIndexed { idx, holding ->
                    trackingNode(
                        code = holding.tsCode,
                        stockNames = stockNames,
                        section = StrategyTrackingSection.HOLDINGS,
                        slotIndex = idx,
                        buyDate = holding.entryDate.toString()
                    )
                }
            // 清仓列：前一交易日持仓中，今日已不在持有的票
            val cleared = if (index == 0) {
                emptyList()
            } else {
                val previousSummary = records[index - 1]
                holdingsByTradeDate[previousSummary.tradeDate].orEmpty()
                    .filterNot { it.tsCode in currentPositionCodes }
                    .take(MAX_TRACKING_SLOT_COUNT)
                    .mapIndexed { idx, holding ->
                        trackingNode(
                            code = holding.tsCode,
                            stockNames = stockNames,
                            section = StrategyTrackingSection.CLEARED,
                            slotIndex = idx,
                            buyDate = holding.entryDate.toString()
                        )
                    }
            }
            StrategyPositionTrackingDay(
                tradeDate = summary.tradeDate.toString(),
                selection = selections,
                holdings = holdings,
                cleared = cleared
            )
        }
    }

    private fun trackingNode(
        code: String,
        stockNames: Map<String, String>,
        section: StrategyTrackingSection,
        slotIndex: Int,
        modelScore: Double? = null,
        entryHint: Double? = null,
        buyDate: String? = null
    ) = StrategyTrackingStockNode(
        stockCode = code,
        stockName = stockNames[code] ?: code,
        section = section,
        slotIndex = slotIndex,
        modelScore = modelScore,
        entryHint = entryHint?.toFloat(),
        buyDate = buyDate
    )

    /**
     * 展示链路业务计算总装：节点盈亏 + 离场判决重建 + 跨日流转边。
     * K 线窗口起点向前扩到最早入场日，保证窗口前延续进来的持仓也能取到成本价。
     */
    private fun enrich(
        days: List<StrategyPositionTrackingDay>,
    ): StrategyPositionTrackingResponse {
        if (days.isEmpty()) return StrategyPositionTrackingResponse(emptyList())
        val allCodes = days.flatMap { day -> day.selection + day.holdings + day.cleared }
            .map { it.stockCode }
            .distinct()
        val firstTradeDate = LocalDate.parse(days.first().tradeDate)
        val endDate = LocalDate.parse(days.last().tradeDate)
        val earliestBuyDate = days.asSequence()
            .flatMap { it.holdings + it.cleared }
            .mapNotNull { node -> node.buyDate?.let(LocalDate::parse) }
            .minOrNull()
        val startDate = minOf(firstTradeDate, earliestBuyDate ?: firstTradeDate)
        // 跟踪页逐日均为已确认交易日，行情统一取 DB 前复权序列（无盘中实时日覆盖）。
        val candles: Map<String, Map<LocalDate, Candle>> = dataSource.loadCandles(allCodes, startDate, endDate)

        val enrichedDays = days.mapIndexed { index, day ->
            val observationDate = LocalDate.parse(day.tradeDate)
            val previousDate = days.getOrNull(index - 1)?.tradeDate?.let(LocalDate::parse)
            day.copy(
                selection = day.selection.map { node ->
                    fillSelectionQuote(node, observationDate, previousDate, candles)
                },
                holdings = day.holdings.map { node ->
                    val filled = fillPnl(node, node.buyDate?.let(LocalDate::parse), observationDate, candles)
                    // 仅对最新观察日的在手持仓推导「下一个可执行卖点」（历史日持仓已成既往，不需提示）
                    if (index == days.lastIndex) fillNextExit(filled, observationDate) else filled
                },
                cleared = day.cleared.map { node ->
                    val filled = fillPnl(node, node.buyDate?.let(LocalDate::parse), observationDate, candles)
                    fillExitVerdict(filled, observationDate, candles)
                }
            )
        }

        return StrategyPositionTrackingResponse(
            days = enrichedDays,
            edges = buildEdges(enrichedDays, candles),
        )
    }

    /**
     * 清仓节点：按生产持仓规则重建离场判决，填充离场原因与规则口径已实现收益。
     *
     * 价格基准 = 前复权（QFQ）：入场价 getOpen 与判决 bar 的 getHigh/getPrice/getLow 均走 *_qfq 列，
     * 与生产推进 HoldingStateMachine.evaluateExit 读同一份 DB *_qfq 列（同源、同锚），判决比值不与生产分叉。
     */
    private fun fillExitVerdict(
        node: StrategyTrackingStockNode,
        exitDate: LocalDate,
        candles: Map<String, Map<LocalDate, Candle>>,
    ): StrategyTrackingStockNode {
        val buyDate = node.buyDate?.let(LocalDate::parse) ?: return node
        val series = candles[node.stockCode] ?: return node
        val buyPrice = series[buyDate]?.getOpen()?.takeIf { it > 0f } ?: return node
        val bar = series[exitDate] ?: return node
        val daysSinceEntry = dataSource.tradingDaysSince(buyDate, exitDate)
        val verdict = holdingStateMachine.evaluateExit(
            holding = DailyHoldingState(
                tradeDate = exitDate,
                tsCode = node.stockCode,
                entryDate = buyDate,
                entryPrice = buyPrice.toDouble(),
                signalDateLow = 0.0,
            ),
            tradeDate = exitDate,
            bar = bar,
            tradingDaysSince = { _, _ -> daysSinceEntry },
        ) ?: return node
        return node.copy(
            exitReason = verdict.reason.toWs(),
            exitPnl = ((verdict.exitPrice - buyPrice) / buyPrice * 100.0).toFloat(),
        )
    }

    /**
     * 持有节点：按当前生效的持仓规则推导「下一个可执行卖点」。
     *
     * 站在观察日收盘视角，次个可卖日 = daysSinceEntry + 1：
     * - 止盈价恒存在（持仓期内任一日 HIGH 触达即止盈）；
     * - 保盈价仅当次个可卖日命中阶梯档位时存在；
     * - 时间止损日 = 入场后第 timeStopDays 个交易日，由交易日历前推得到。
     *
     * 价格基准与持有节点展示口径一致：入场价取信号窗内入场日开盘（QFQ 优先，[fillPnl] 同源），
     * 触发价为入场价乘以规则比例。
     */
    private fun fillNextExit(
        node: StrategyTrackingStockNode,
        observationDate: LocalDate,
    ): StrategyTrackingStockNode {
        val buyDate = node.buyDate?.let(LocalDate::parse) ?: return node
        val entryPrice = node.buyPrice?.takeIf { it > 0f } ?: return node
        val rules = holdingStateMachine.rules
        val daysSinceEntry = dataSource.tradingDaysSince(buyDate, observationDate)
        // 次个可卖日距入场的交易日序号（1 = 入场次日首个可卖日）
        val nextSellOrdinal = (daysSinceEntry + 1).coerceAtLeast(1)
        val takeProfitPrice = entryPrice * (1.0 + rules.takeProfitPct).toFloat()
        val profitProtectPrice = rules.profitProtectLadder[nextSellOrdinal]
            ?.let { lv -> entryPrice * (1.0 + lv).toFloat() }
        // 时间止损：入场后第 timeStopDays 个交易日收盘强平；距今交易日数 = timeStopDays - 1 - daysSinceEntry
        val timeStopRemaining = (rules.timeStopDays - 1 - daysSinceEntry).coerceAtLeast(0)
        val timeStopDate = dataSource.tradingDayAfter(observationDate, timeStopRemaining)
        return node.copy(
            nextExit = StrategyTrackingNextExit(
                takeProfitPrice = takeProfitPrice,
                profitProtectPrice = profitProtectPrice,
                timeStopDate = timeStopDate?.toString(),
                timeStopInTradingDays = timeStopRemaining,
            )
        )
    }

    /** 选股节点：观察日收盘价与当日涨跌幅。 */
    private fun fillSelectionQuote(
        node: StrategyTrackingStockNode,
        observationDate: LocalDate,
        previousDate: LocalDate?,
        candles: Map<String, Map<LocalDate, Candle>>,
    ): StrategyTrackingStockNode {
        val series = candles[node.stockCode] ?: return node
        val current = series[observationDate]?.getPrice()?.takeIf { it > 0f } ?: return node
        val previousClose = previousDate?.let { series[it]?.getPrice() }?.takeIf { it > 0f }
        return node.copy(
            currentPrice = current,
            dayChangePct = previousClose?.let { (current - it) / it * 100f },
        )
    }

    private fun buildEdges(
        days: List<StrategyPositionTrackingDay>,
        candles: Map<String, Map<LocalDate, Candle>>,
    ): List<StrategyTrackingEdge> {
        fun candleAt(code: String, date: LocalDate): Candle? = candles[code]?.get(date)

        val edges = mutableListOf<StrategyTrackingEdge>()
        days.zipWithNext { current, next ->
            val currentDate = LocalDate.parse(current.tradeDate)
            val nextDate = LocalDate.parse(next.tradeDate)
            val nextHoldings = next.holdings.associateBy { it.stockCode }
            val nextCleared = next.cleared.associateBy { it.stockCode }
            for (from in current.holdings) {
                // 持有主干：目标日当日涨跌幅
                nextHoldings[from.stockCode]?.let { to ->
                    val previousClose = candleAt(from.stockCode, currentDate)?.getPrice()?.takeIf { it > 0f }
                    val currentClose = candleAt(from.stockCode, nextDate)?.getPrice()?.takeIf { it > 0f }
                    edges += edge(
                        from = from, to = to,
                        fromDate = current.tradeDate, toDate = next.tradeDate,
                        kind = StrategyTrackingEdgeKind.HOLD_CONTINUE,
                        pnlPct = if (previousClose != null && currentClose != null) {
                            (currentClose - previousClose) / previousClose * 100f
                        } else null,
                    )
                }
                // 清仓支路：规则口径已实现收益 + 离场原因；判决缺失时回退收盘口径
                nextCleared[from.stockCode]?.let { to ->
                    edges += edge(
                        from = from, to = to,
                        fromDate = current.tradeDate, toDate = next.tradeDate,
                        kind = StrategyTrackingEdgeKind.EXIT_CLEAR,
                        pnlPct = to.exitPnl ?: to.actualPnl,
                        exitReason = to.exitReason,
                    )
                }
            }
        }
        // 入场支路（跨多日追溯）：入场日 == 当天且前一交易日未持有的新进持仓，
        // 连回前一交易日的选股节点；盈亏 = 入场日开盘 → 收盘
        for (index in 1 until days.size) {
            val buyDay = days[index]
            val selectionDay = days[index - 1]
            val buyDate = LocalDate.parse(buyDay.tradeDate)
            val previousHoldingCodes = selectionDay.holdings.mapTo(mutableSetOf()) { it.stockCode }
            val selectionByCode = selectionDay.selection.associateBy { it.stockCode }
            for (holding in buyDay.holdings) {
                if (holding.buyDate != buyDay.tradeDate) continue
                if (holding.stockCode in previousHoldingCodes) continue
                val from = selectionByCode[holding.stockCode] ?: continue
                val bar = candleAt(holding.stockCode, buyDate)
                val open = bar?.getOpen()?.takeIf { it > 0f }
                val close = bar?.getPrice()?.takeIf { it > 0f }
                edges += edge(
                    from = from, to = holding,
                    fromDate = selectionDay.tradeDate, toDate = buyDay.tradeDate,
                    kind = StrategyTrackingEdgeKind.ENTER_HOLDING,
                    pnlPct = if (open != null && close != null) (close - open) / open * 100f else null,
                )
            }
        }
        return edges
    }

    private fun edge(
        from: StrategyTrackingStockNode,
        to: StrategyTrackingStockNode,
        fromDate: String,
        toDate: String,
        kind: StrategyTrackingEdgeKind,
        pnlPct: Float? = null,
        exitReason: StrategyTrackingExitReason? = null,
    ) = StrategyTrackingEdge(
        fromDate = fromDate,
        fromSection = from.section,
        fromStockCode = from.stockCode,
        fromSlotIndex = from.slotIndex,
        toDate = toDate,
        toSection = to.section,
        toStockCode = to.stockCode,
        toSlotIndex = to.slotIndex,
        kind = kind,
        pnlPct = pnlPct,
        exitReason = exitReason,
    )

    private fun HoldingStateMachine.ExitReason.toWs(): StrategyTrackingExitReason = when (this) {
        HoldingStateMachine.ExitReason.TAKE_PROFIT -> StrategyTrackingExitReason.TAKE_PROFIT
        HoldingStateMachine.ExitReason.PROFIT_PROTECT -> StrategyTrackingExitReason.PROFIT_PROTECT
        HoldingStateMachine.ExitReason.SHALLOW_STOP -> StrategyTrackingExitReason.SHALLOW_STOP
        HoldingStateMachine.ExitReason.TIME_STOP -> StrategyTrackingExitReason.TIME_STOP
        HoldingStateMachine.ExitReason.PRICE_STOP -> StrategyTrackingExitReason.PRICE_STOP
    }
}

interface StrategyPositionTrackingDataSource {
    fun loadAuditSummaries(limit: Int): List<StrategyAuditSummary>
    fun loadSelectionsByTradeDate(tradeDates: List<LocalDate>): Map<LocalDate, List<ProfitPredictionSelection>>
    /** 按交易日加载持仓状态快照（含 entryDate），key=tradeDate。 */
    fun loadHoldingsByTradeDate(tradeDates: List<LocalDate>): Map<LocalDate, List<DailyHoldingState>>
    fun loadStockNames(tsCodes: Collection<String>): Map<String, String>
    fun loadCandles(tsCodes: List<String>, startDate: LocalDate, endDate: LocalDate): Map<String, Map<LocalDate, Candle>>
    /** 交易日间隔（不含 start，含 end），口径必须与生产状态机推进一致。 */
    fun tradingDaysSince(entryDate: LocalDate, date: LocalDate): Int
    /** 从 [date] 向后第 [tradingDays] 个交易日（0 = [date] 当日）；超出已知日历返回 null。 */
    fun tradingDayAfter(date: LocalDate, tradingDays: Int): LocalDate?
    /** 校准重放入场候选：target_date = [targetDate] 的 selected 票（与盘后状态机推进同一来源），行自带信号日 tradeDate。 */
    fun loadSelectionsByTargetDate(targetDate: LocalDate): List<ProfitPredictionSelection>
}

object DefaultStrategyPositionTrackingDataSource : StrategyPositionTrackingDataSource {
    override fun loadAuditSummaries(limit: Int): List<StrategyAuditSummary> =
        DailyStrategyAuditRepository.getRecentRecords(limit)

    override fun loadSelectionsByTradeDate(tradeDates: List<LocalDate>): Map<LocalDate, List<ProfitPredictionSelection>> =
        DailyProfitPredictionSelectionRepository.findSelectionsByTradeDates(tradeDates)

    override fun loadHoldingsByTradeDate(tradeDates: List<LocalDate>): Map<LocalDate, List<DailyHoldingState>> =
        tradeDates.distinct().associateWith { date ->
            DailyStrategyHoldingRepository.findByTradeDate(date)
        }

    override fun loadStockNames(tsCodes: Collection<String>): Map<String, String> =
        StockReader.getStockNames(tsCodes)

    override fun loadCandles(
        tsCodes: List<String>,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<String, Map<LocalDate, Candle>> =
        tsCodes.distinct().associateWith { code ->
            StockReader.getStockHistory(code, startDate, endDate).associateBy { it.date }
        }

    override fun tradingDaysSince(entryDate: LocalDate, date: LocalDate): Int =
        if (date <= entryDate) 0
        else (TradingCalendarRepository.findOpenDates(entryDate, date).size - 1).coerceAtLeast(0)

    override fun tradingDayAfter(date: LocalDate, tradingDays: Int): LocalDate? {
        if (tradingDays <= 0) return date
        // 含自然日缓冲（周末 + 法定假期）足够覆盖 tradingDays 个交易日
        val upper = date.plus(DatePeriod(days = tradingDays * 2 + 14))
        return TradingCalendarRepository.findOpenDates(date, upper).getOrNull(tradingDays)
    }

    override fun loadSelectionsByTargetDate(targetDate: LocalDate): List<ProfitPredictionSelection> =
        DailyProfitPredictionSelectionRepository.findSelectionsByTargetDate(targetDate)
}

private fun fillPnl(
    node: StrategyTrackingStockNode,
    buyDate: LocalDate?,
    observationDate: LocalDate,
    candleMap: Map<String, Map<LocalDate, Candle>>
): StrategyTrackingStockNode {
    if (buyDate == null) return node
    val candles = candleMap[node.stockCode] ?: return node
    val buyCandle = candles[buyDate] ?: return node
    val currentCandle = candles[observationDate] ?: return node
    val buyPrice = buyCandle.getOpen()
    if (buyPrice <= 0) return node

    val currentPrice = currentCandle.getPrice()
    val actualPnl = ((currentPrice - buyPrice) / buyPrice) * 100f
    val maxHigh = candles
        .filter { (date, _) -> date > buyDate && date <= observationDate }
        .values
        .maxOfOrNull { it.getHigh() }
    val maxPnl = maxHigh?.let { ((it - buyPrice) / buyPrice) * 100f }

    return node.copy(
        buyDate = buyDate.toString(),
        buyPrice = buyPrice,
        actualPnl = actualPnl,
        maxPnl = maxPnl,
        currentPrice = currentPrice,
    )
}
