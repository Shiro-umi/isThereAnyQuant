package org.shiroumi.strategy.service.runtime

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import model.Candle
import model.candle.StrategyPositionTrackingDay
import model.candle.StrategyPositionTrackingResponse
import model.candle.StrategyTrackingEdge
import model.candle.StrategyTrackingEdgeKind
import model.candle.StrategyTrackingExitReason
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
import org.shiroumi.strategy.service.postmarket.HoldingStateMachine
import utils.logger

private const val DEFAULT_TRACKING_LIMIT = 60
private const val MAX_TRACKING_SLOT_COUNT = 5

/**
 * 持仓跟踪时间线 runtime——`STRATEGY_POSITION_TRACKING` 快照的唯一 owner。
 *
 * 展示链路的全部业务计算在此完成，前端只负责渲染：
 * - 节点盈亏：持有/清仓节点的成本（入场日开盘）、现价（历史日收盘 / 盘中实时价）、
 *   浮动收益与持有期最高收益；选股节点的观察日价格与当日涨跌幅。
 * - 离场判决重建：清仓节点通过 [HoldingStateMachine.evaluateExit] 按生产持仓规则
 *   （[HoldingStateMachine.ExitRules.fromSystemProperties]，与盘后推进同一装配入口）
 *   重建离场原因与规则口径已实现收益（止盈/保盈按触价含高开穿越、到期按收盘）。
 * - 跨日流转边：持有主干 / 选股→次日买入 / 持有→清仓三类边及各自盈亏百分比
 *   （持有边=目标日当日涨跌、买入边=入场日开盘→收盘、卖出边=规则口径已实现收益）。
 *
 * 行情口径：DB 日 K 取前复权优先（[Candle.getOpen] 系访问器，锚 = 库内最新行 adj）；
 * 盘中实时 K 由注入方（IntradayStrategyRuntime）经 withRuntimeQfq 重锚到同一基准后传入，
 * 除权除息日盘中亦同基准。离场判决重建使用 raw 对 raw 基准（与生产推进的决策基准一致，
 * 见 [fillExitVerdict]）。价格止损（生产默认关闭）的判决重建不还原信号日最低价，
 * 无法命中 PRICE_STOP 的清仓节点按收盘口径回退展示且不带原因标签。
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
     * @param realtimeCandles 盘中实时日 K（tsCode -> 当日实时 Candle），仅
     *   [PositionSource.INTRADAY_REALTIME] 时由盘中 runtime 注入，用于实时日盈亏与流转边计算。
     */
    suspend fun publishFromPositions(
        positionSnapshot: StrategyPositionSnapshot,
        realtimeCandles: Map<String, Candle> = emptyMap(),
    ): StrategySnapshotEnvelope<JsonElement>? {
        val tracking = when (positionSnapshot.source) {
            PositionSource.HISTORICAL_AUDIT -> null
            PositionSource.DAILY_AUDIT_COMPLETE -> buildHistoricalSnapshot()
            PositionSource.INTRADAY_REALTIME -> {
                val current = currentTracking()
                    ?: buildHistoricalSnapshot()
                    ?: return null
                updateRealtimeDay(current, positionSnapshot, realtimeCandles)
            }
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

    private suspend fun currentTracking(): StrategyPositionTrackingResponse? =
        snapshotHub.current(StrategyTopic.POSITION_TRACKING)
            ?.payload
            ?.let { payload ->
                runCatching {
                    json.decodeFromJsonElement(StrategyPositionTrackingResponse.serializer(), payload)
                }.getOrNull()
            }

    private fun buildHistoricalSnapshot(): StrategyPositionTrackingResponse? {
        val records = dataSource.loadAuditSummaries(trackingLimit).reversed()
        if (records.isEmpty()) return null

        val tradeDates = records.map { it.tradeDate }
        val selectionsByTradeDate = dataSource.loadSelectionsByTradeDate(tradeDates)
        val holdingsByTradeDate = dataSource.loadHoldingsByTradeDate(tradeDates)
        val allCodes = records.flatMap { summary ->
            val selections = selectionsByTradeDate[summary.tradeDate].orEmpty().map { it.tsCode }
            val holdings = holdingsByTradeDate[summary.tradeDate].orEmpty().map { it.tsCode }
            selections + holdings
        }.distinct()
        val stockNames = dataSource.loadStockNames(allCodes)

        val days = records.mapIndexed { index, summary ->
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
                        modelScore = selection.modelScore
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

        return enrich(days, realtimeCandles = emptyMap(), realtimeTradeDate = null)
    }

    private fun updateRealtimeDay(
        current: StrategyPositionTrackingResponse,
        positionSnapshot: StrategyPositionSnapshot,
        realtimeCandles: Map<String, Candle>,
    ): StrategyPositionTrackingResponse {
        val tradeDate = positionSnapshot.tradeDate
        val currentDays = current.days
        if (
            positionSnapshot.source == PositionSource.INTRADAY_REALTIME &&
            currentDays.lastOrNull()?.tradeDate?.let {
                LocalDate.parse(tradeDate) < LocalDate.parse(it)
            } == true
        ) {
            return current
        }

        val baseDays = when {
            currentDays.isEmpty() -> emptyList()
            currentDays.last().tradeDate == tradeDate -> currentDays.dropLast(1)
            else -> currentDays
        }
        val currentPositions = positionSnapshot.currentPositions.take(MAX_TRACKING_SLOT_COUNT)
        val currentPositionCodes = currentPositions.toSet()
        val previousHoldingNodes = baseDays.lastOrNull()?.holdings.orEmpty()
        val previousHoldings = previousHoldingNodes.map { it.stockCode }.toSet()
        // 实时持仓 buyDate 继承上一交易日同票的 entryDate；若是当日新进则记为当日
        val buyDateByCode = previousHoldingNodes.associate { it.stockCode to it.buyDate }
        val realtimeSelections = positionSnapshot.nextSessionSelectionDetails
            .takeIf { it.isNotEmpty() }
            ?: positionSnapshot.nextSessionSelections.map {
                model.ws.StrategySelectionSnapshot(tsCode = it, modelScore = 0.0)
            }
        val codes = (currentPositions + previousHoldings + realtimeSelections.map { it.tsCode }).distinct()
        val stockNames = dataSource.loadStockNames(codes)

        val realtimeDay = StrategyPositionTrackingDay(
            tradeDate = tradeDate,
            selection = realtimeSelections
                .filterNot { it.tsCode in currentPositionCodes }
                .take(MAX_TRACKING_SLOT_COUNT)
                .mapIndexed { idx, selection ->
                    trackingNode(
                        code = selection.tsCode,
                        stockNames = stockNames,
                        section = StrategyTrackingSection.SELECTION,
                        slotIndex = idx,
                        modelScore = selection.modelScore
                    )
                },
            holdings = currentPositions.mapIndexed { idx, code ->
                trackingNode(
                    code, stockNames, StrategyTrackingSection.HOLDINGS, idx,
                    buyDate = buyDateByCode[code] ?: tradeDate
                )
            },
            cleared = previousHoldingNodes
                .filterNot { it.stockCode in currentPositionCodes }
                .take(MAX_TRACKING_SLOT_COUNT)
                .mapIndexed { idx, node ->
                    trackingNode(
                        node.stockCode, stockNames, StrategyTrackingSection.CLEARED, idx,
                        buyDate = node.buyDate
                    )
                }
        )
        return enrich(baseDays + realtimeDay, realtimeCandles, realtimeTradeDate = tradeDate)
    }

    private fun trackingNode(
        code: String,
        stockNames: Map<String, String>,
        section: StrategyTrackingSection,
        slotIndex: Int,
        modelScore: Double? = null,
        buyDate: String? = null
    ) = StrategyTrackingStockNode(
        stockCode = code,
        stockName = stockNames[code] ?: code,
        section = section,
        slotIndex = slotIndex,
        modelScore = modelScore,
        buyDate = buyDate
    )

    /**
     * 展示链路业务计算总装：节点盈亏 + 离场判决重建 + 跨日流转边。
     * K 线窗口起点向前扩到最早入场日，保证窗口前延续进来的持仓也能取到成本价。
     */
    private fun enrich(
        days: List<StrategyPositionTrackingDay>,
        realtimeCandles: Map<String, Candle>,
        realtimeTradeDate: String?,
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
        val dbCandles = dataSource.loadCandles(allCodes, startDate, endDate)
        // 实时日 K 覆盖到同一份序列上，下游统一按日期取价
        val realtimeDate = realtimeTradeDate?.let(LocalDate::parse)
        val candles: Map<String, Map<LocalDate, Candle>> = if (realtimeDate == null) {
            dbCandles
        } else {
            allCodes.associateWith { code ->
                val base = dbCandles[code].orEmpty()
                realtimeCandles[code]?.let { base + (realtimeDate to it) } ?: base
            }
        }

        val enrichedDays = days.mapIndexed { index, day ->
            val observationDate = LocalDate.parse(day.tradeDate)
            val previousDate = days.getOrNull(index - 1)?.tradeDate?.let(LocalDate::parse)
            day.copy(
                selection = day.selection.map { node ->
                    fillSelectionQuote(node, observationDate, previousDate, candles)
                },
                holdings = day.holdings.map { node ->
                    fillPnl(node, node.buyDate?.let(LocalDate::parse), observationDate, candles)
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
            realtimeTradeDate = realtimeTradeDate,
        )
    }

    /**
     * 清仓节点：按生产持仓规则重建离场判决，填充离场原因与规则口径已实现收益。
     *
     * 价格基准 = raw 对 raw：生产推进的 entry_price 与判决 bar 都是「各自当日锚」价（≈raw），
     * 重建若用当前锚 QFQ，持仓窗口内发生除权时比值会与生产决策分叉（错标原因或丢失判决）。
     */
    private fun fillExitVerdict(
        node: StrategyTrackingStockNode,
        exitDate: LocalDate,
        candles: Map<String, Map<LocalDate, Candle>>,
    ): StrategyTrackingStockNode {
        val buyDate = node.buyDate?.let(LocalDate::parse) ?: return node
        val series = candles[node.stockCode] ?: return node
        val buyPrice = series[buyDate]?.open?.takeIf { it > 0f } ?: return node
        val bar = series[exitDate]?.asRawBar() ?: return node
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

    /** 清空 QFQ 字段使取价访问器回退 raw，与生产推进的当日锚基准一致。 */
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    private fun Candle.asRawBar(): Candle =
        copy(openQfq = 0f, highQfq = 0f, lowQfq = 0f, closeQfq = 0f)

    /** 选股节点：观察日价格与当日涨跌幅（盘中实时日即实时价/实时涨跌）。 */
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
