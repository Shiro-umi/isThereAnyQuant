package org.shiroumi.strategy.service.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import model.Candle
import model.candle.StrategyPositionTrackingResponse
import model.ws.PositionSource
import model.ws.StrategyPositionSnapshot
import org.shiroumi.database.strategy.daily.repository.DailyTargetSelection
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.core.audit.StrategyAuditSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class StrategyPositionTrackingRuntimeTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val day1 = LocalDate(2026, 4, 29)
    private val day2 = LocalDate(2026, 4, 30)

    @Test
    fun `publishes service position tracking snapshot from positions update`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val runtime = StrategyPositionTrackingRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = FakeTrackingDataSource(
                audits = listOf(
                    audit(day2, currentPositions = listOf("000002.SZ")),
                    audit(day1, currentPositions = listOf("000001.SZ"))
                ),
                selectionsByTradeDate = mapOf(
                    day1 to listOf(selection(day1, "000002.SZ")),
                    day2 to listOf(selection(day2, "000003.SZ"))
                ),
                holdingsByTargetDate = mapOf(
                    day1 to listOf(selection(day1, "000001.SZ")),
                    day2 to listOf(selection(day1, "000002.SZ"))
                ),
                names = mapOf(
                    "000001.SZ" to "A",
                    "000002.SZ" to "B",
                    "000003.SZ" to "C",
                    "000004.SZ" to "D"
                ),
                candles = mapOf(
                    "000001.SZ" to mapOf(day1 to candle("000001.SZ", day1, open = 10f, close = 11f)),
                    "000002.SZ" to mapOf(day2 to candle("000002.SZ", day2, open = 20f, close = 22f))
                )
            )
        )

        val envelope = runtime.publishFromPositions(
            StrategyPositionSnapshot(
                tradeDate = day2.toString(),
                currentPositions = listOf("000002.SZ"),
                source = PositionSource.DAILY_AUDIT_COMPLETE,
                nextSessionSelections = listOf("000003.SZ"),
                newlySelected = listOf("000003.SZ")
            )
        )

        assertNotNull(envelope)
        val stored = assertNotNull(hub.current(StrategyTopic.POSITION_TRACKING))
        val payload = json.decodeFromJsonElement(
            StrategyPositionTrackingResponse.serializer(),
            stored.payload
        )
        assertEquals(listOf("2026-04-29", "2026-04-30"), payload.days.map { it.tradeDate })
        assertEquals(listOf("000003.SZ"), payload.days.last().selection.map { it.stockCode })
        assertEquals(listOf("000002.SZ"), payload.days.last().holdings.map { it.stockCode })
        assertEquals(StrategyTopic.POSITION_TRACKING, envelope.topic)

        runtime.publishFromPositions(
            StrategyPositionSnapshot(
                tradeDate = day2.toString(),
                currentPositions = listOf("000002.SZ"),
                source = PositionSource.INTRADAY_REALTIME,
                nextSessionSelections = listOf("000004.SZ"),
                newlySelected = listOf("000004.SZ")
            )
        )

        val updated = json.decodeFromJsonElement(
            StrategyPositionTrackingResponse.serializer(),
            assertNotNull(hub.current(StrategyTopic.POSITION_TRACKING)).payload
        )
        assertEquals(listOf("000004.SZ"), updated.days.last().selection.map { it.stockCode })
    }

    private fun audit(
        tradeDate: LocalDate,
        currentPositions: List<String>
    ) = StrategyAuditSummary(
        tradeDate = tradeDate,
        universeSize = 10,
        signalPositiveCount = 3,
        selectedCount = currentPositions.size,
        emptyReason = null,
        newlySelected = emptyList(),
        dropped = emptyList(),
        currentPositions = currentPositions,
        sentimentExposure = 1.0,
        bullRatio = 0.5,
        marketVol = 0.01,
        fftScore = 0.5,
        residualScore = 0.5,
        accelZ = 0.0,
        volZ = 0.0,
        ratioNorm = 0.5,
        volScore = 0.5,
        accelScore = 0.5,
        absoluteFloor = 0.256,
        volCap = 1.0
    )

    private fun selection(tradeDate: LocalDate, tsCode: String) =
        DailyTargetSelection(
            tradeDate = tradeDate,
            tsCode = tsCode,
            selectionScore = 1.0
        )

    private fun candle(tsCode: String, date: LocalDate, open: Float, close: Float) =
        Candle(
            tsCode = tsCode,
            date = date,
            open = open,
            high = maxOf(open, close),
            low = minOf(open, close),
            close = close,
            adj = 1f,
            volume = 100f,
            turnoverReal = 0f,
            pe = 0f,
            peTtm = 0f,
            pb = 0f,
            ps = 0f,
            psTtm = 0f,
            mvTotal = 0f,
            mvCirc = 0f
        )
}

private class FakeTrackingDataSource(
    private val audits: List<StrategyAuditSummary>,
    private val selectionsByTradeDate: Map<LocalDate, List<DailyTargetSelection>>,
    private val holdingsByTargetDate: Map<LocalDate, List<DailyTargetSelection>>,
    private val names: Map<String, String>,
    private val candles: Map<String, Map<LocalDate, Candle>>
) : StrategyPositionTrackingDataSource {
    override fun loadAuditSummaries(limit: Int): List<StrategyAuditSummary> = audits.take(limit)

    override fun loadSelectionsByTradeDate(tradeDates: List<LocalDate>): Map<LocalDate, List<DailyTargetSelection>> =
        selectionsByTradeDate.filterKeys { it in tradeDates }

    override fun loadHoldingsByTargetDate(targetDates: List<LocalDate>): Map<LocalDate, List<DailyTargetSelection>> =
        holdingsByTargetDate.filterKeys { it in targetDates }

    override fun loadStockNames(tsCodes: Collection<String>): Map<String, String> =
        names.filterKeys { it in tsCodes }

    override fun loadCandles(
        tsCodes: List<String>,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<String, Map<LocalDate, Candle>> =
        candles.filterKeys { it in tsCodes }
}
