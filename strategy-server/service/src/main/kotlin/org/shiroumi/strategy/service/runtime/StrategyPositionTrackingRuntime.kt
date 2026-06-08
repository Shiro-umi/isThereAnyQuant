package org.shiroumi.strategy.service.runtime

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import model.Candle
import model.candle.StrategyPositionTrackingDay
import model.candle.StrategyPositionTrackingResponse
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode
import model.ws.PositionSource
import model.ws.StrategyPositionSnapshot
import org.shiroumi.database.stock.StockReader
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository
import org.shiroumi.database.strategy.daily.repository.ProfitPredictionSelection
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.core.audit.StrategyAuditSummary
import utils.logger

private const val DEFAULT_TRACKING_LIMIT = 60
private const val MAX_TRACKING_SLOT_COUNT = 5

class StrategyPositionTrackingRuntime(
    private val snapshotHub: LocalStrategySnapshotHub<JsonElement>,
    private val json: Json,
    private val dataSource: StrategyPositionTrackingDataSource = DefaultStrategyPositionTrackingDataSource,
    private val trackingLimit: Int = DEFAULT_TRACKING_LIMIT
) {
    private val logger by logger("StrategyPositionTrackingRuntime")

    suspend fun publishFromPositions(
        positionSnapshot: StrategyPositionSnapshot
    ): StrategySnapshotEnvelope<JsonElement>? {
        val tracking = when (positionSnapshot.source) {
            PositionSource.HISTORICAL_AUDIT -> null
            PositionSource.DAILY_AUDIT_COMPLETE -> buildHistoricalSnapshot()
            PositionSource.INTRADAY_REALTIME -> {
                val current = currentTracking()
                    ?: buildHistoricalSnapshot()
                    ?: return null
                updateRealtimeDay(current, positionSnapshot)
            }
        } ?: return null

        val envelope = snapshotHub.publish(
            StrategyTopic.POSITION_TRACKING,
            json.encodeToJsonElement(StrategyPositionTrackingResponse.serializer(), tracking)
        )
        logger.info(
            "strategy-service position tracking published source=${positionSnapshot.source} " +
                "tradeDate=${positionSnapshot.tradeDate} days=${tracking.days.size} version=${envelope.version}"
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
        val holdingsByTargetDate = dataSource.loadHoldingsByTargetDate(tradeDates)
        val allCodes = records.flatMap { summary ->
            val selections = selectionsByTradeDate[summary.tradeDate].orEmpty().map { it.tsCode }
            val holdings = summary.positionSymbols(holdingsByTargetDate[summary.tradeDate].orEmpty())
            selections + holdings
        }.distinct()
        val stockNames = dataSource.loadStockNames(allCodes)

        val days = records.mapIndexed { index, summary ->
            val currentPositions = summary.positionSymbols(holdingsByTargetDate[summary.tradeDate].orEmpty())
            val currentPositionCodes = currentPositions.toSet()
            val selections = selectionsByTradeDate[summary.tradeDate]
                .orEmpty()
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
                }
            val holdings = currentPositions.mapIndexed { idx, code ->
                trackingNode(
                    code = code,
                    stockNames = stockNames,
                    section = StrategyTrackingSection.HOLDINGS,
                    slotIndex = idx
                )
            }
            val cleared = if (index == 0) {
                emptyList()
            } else {
                val previousSummary = records[index - 1]
                previousSummary.positionSymbols(holdingsByTargetDate[previousSummary.tradeDate].orEmpty())
                    .filterNot { it in currentPositionCodes }
                    .take(MAX_TRACKING_SLOT_COUNT)
                    .mapIndexed { idx, code ->
                        trackingNode(
                            code = code,
                            stockNames = stockNames,
                            section = StrategyTrackingSection.CLEARED,
                            slotIndex = idx
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

        return enrichPnl(days)
    }

    private fun updateRealtimeDay(
        current: StrategyPositionTrackingResponse,
        positionSnapshot: StrategyPositionSnapshot
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
        val previousHoldings = currentDays.lastOrNull()?.holdings?.map { it.stockCode }?.toSet().orEmpty()
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
                trackingNode(code, stockNames, StrategyTrackingSection.HOLDINGS, idx)
            },
            cleared = previousHoldings
                .filterNot { it in currentPositionCodes }
                .take(MAX_TRACKING_SLOT_COUNT)
                .mapIndexed { idx, code ->
                    trackingNode(code, stockNames, StrategyTrackingSection.CLEARED, idx)
                }
        )
        return enrichPnl(baseDays + realtimeDay)
    }

    private fun trackingNode(
        code: String,
        stockNames: Map<String, String>,
        section: StrategyTrackingSection,
        slotIndex: Int,
        modelScore: Double? = null
    ) = StrategyTrackingStockNode(
        stockCode = code,
        stockName = stockNames[code] ?: code,
        section = section,
        slotIndex = slotIndex,
        modelScore = modelScore
    )

    private fun enrichPnl(days: List<StrategyPositionTrackingDay>): StrategyPositionTrackingResponse {
        if (days.isEmpty()) return StrategyPositionTrackingResponse(emptyList())
        val buyDatesByCode = buildCycleBuyDates(days)
        val allCodes = days.flatMap { day -> day.holdings + day.cleared }
            .map { it.stockCode }
            .distinct()
        val startDate = days.firstOrNull()?.tradeDate?.let(LocalDate::parse)
        val endDate = days.lastOrNull()?.tradeDate?.let(LocalDate::parse)
        val candleMap = if (startDate != null && endDate != null) {
            dataSource.loadCandles(allCodes, startDate, endDate)
        } else {
            emptyMap()
        }

        return StrategyPositionTrackingResponse(
            days = days.map { day ->
                val observationDate = LocalDate.parse(day.tradeDate)
                day.copy(
                    holdings = day.holdings.map { node ->
                        fillPnl(node, buyDatesByCode[node.stockCode]?.get(observationDate), observationDate, candleMap)
                    },
                    cleared = day.cleared.map { node ->
                        fillPnl(node, buyDatesByCode[node.stockCode]?.get(observationDate), observationDate, candleMap)
                    }
                )
            }
        )
    }
}

interface StrategyPositionTrackingDataSource {
    fun loadAuditSummaries(limit: Int): List<StrategyAuditSummary>
    fun loadSelectionsByTradeDate(tradeDates: List<LocalDate>): Map<LocalDate, List<ProfitPredictionSelection>>
    fun loadHoldingsByTargetDate(targetDates: List<LocalDate>): Map<LocalDate, List<ProfitPredictionSelection>>
    fun loadStockNames(tsCodes: Collection<String>): Map<String, String>
    fun loadCandles(tsCodes: List<String>, startDate: LocalDate, endDate: LocalDate): Map<String, Map<LocalDate, Candle>>
}

object DefaultStrategyPositionTrackingDataSource : StrategyPositionTrackingDataSource {
    override fun loadAuditSummaries(limit: Int): List<StrategyAuditSummary> =
        DailyStrategyAuditRepository.getRecentRecords(limit)

    override fun loadSelectionsByTradeDate(tradeDates: List<LocalDate>): Map<LocalDate, List<ProfitPredictionSelection>> =
        DailyProfitPredictionSelectionRepository.findSelectionsByTradeDates(tradeDates)

    override fun loadHoldingsByTargetDate(targetDates: List<LocalDate>): Map<LocalDate, List<ProfitPredictionSelection>> =
        DailyProfitPredictionSelectionRepository.findSelectionsByTargetDates(targetDates)

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
}

private fun StrategyAuditSummary.positionSymbols(
    fallbackTargetSelections: List<ProfitPredictionSelection>
): List<String> {
    val auditSymbols = currentPositions.take(MAX_TRACKING_SLOT_COUNT)
    if (auditSymbols.isNotEmpty()) return auditSymbols
    return fallbackTargetSelections.map { it.tsCode }.take(MAX_TRACKING_SLOT_COUNT)
}

private fun buildCycleBuyDates(
    days: List<StrategyPositionTrackingDay>
): Map<String, Map<LocalDate, LocalDate>> {
    if (days.isEmpty()) return emptyMap()

    val activeBuyDates = mutableMapOf<String, LocalDate>()
    val buyDatesByCode = mutableMapOf<String, MutableMap<LocalDate, LocalDate>>()
    var previousHoldingCodes = emptySet<String>()

    days.forEach { day ->
        val tradeDate = LocalDate.parse(day.tradeDate)
        val holdingCodes = day.holdings.map { it.stockCode }.toSet()
        val enteredHoldingCodes = holdingCodes - previousHoldingCodes

        enteredHoldingCodes.forEach { stockCode ->
            activeBuyDates[stockCode] = tradeDate
        }

        val observedNodes = day.holdings + day.cleared
        observedNodes.forEach { node ->
            val buyDate = activeBuyDates[node.stockCode] ?: return@forEach
            buyDatesByCode.getOrPut(node.stockCode) { mutableMapOf() }[tradeDate] = buyDate
        }

        activeBuyDates.keys.retainAll(holdingCodes)
        previousHoldingCodes = holdingCodes
    }

    return buyDatesByCode
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
        maxPnl = maxPnl
    )
}
