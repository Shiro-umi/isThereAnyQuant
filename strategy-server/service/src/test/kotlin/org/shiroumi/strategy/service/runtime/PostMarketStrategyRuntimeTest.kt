package org.shiroumi.strategy.service.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import model.ws.PositionSource
import model.ws.StrategyPositionSnapshot
import org.shiroumi.database.strategy.daily.repository.ProfitPredictionSelection
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.core.audit.StrategyAuditSummary
import org.shiroumi.strategy.service.postmarket.PostMarketOrchestrator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PostMarketStrategyRuntimeTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val tradeDate = LocalDate(2026, 4, 30)

    @Test
    fun `startup hydration publishes latest daily positions from audit`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val dataSource = FakePostMarketDataSource(
            executionResult = PostMarketOrchestrator.ExecutionResult(processedDates = emptyList()),
            auditSummary = auditSummary(
                tradeDate = tradeDate,
                currentPositions = listOf("000001.SZ"),
                newlySelected = listOf("000002.SZ")
            ),
            nextSelections = listOf(selection("000001.SZ", 0.72), selection("000002.SZ", 0.91))
        )
        val runtime = PostMarketStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = dataSource
        )

        val result = runtime.publishLatestPositions("startup-test")

        assertTrue(result.accepted)
        val envelope = assertNotNull(hub.current(StrategyTopic.POSITIONS))
        val payload = json.decodeFromJsonElement(StrategyPositionSnapshot.serializer(), envelope.payload)
        assertEquals("2026-04-30", payload.tradeDate)
        assertEquals(PositionSource.DAILY_AUDIT_COMPLETE, payload.source)
        assertEquals(listOf("000001.SZ"), payload.currentPositions)
        assertEquals(listOf("000002.SZ", "000001.SZ"), payload.nextSessionSelections)
        assertEquals(listOf(0.91, 0.72), payload.nextSessionSelectionDetails.map { it.modelScore })
        assertEquals(listOf("000002.SZ"), payload.newlySelected)
    }

    @Test
    fun `rebuild date executes post market strategy and publishes daily positions`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val dataSource = FakePostMarketDataSource(
            executionResult = PostMarketOrchestrator.ExecutionResult(processedDates = listOf(tradeDate)),
            auditSummary = auditSummary(
                tradeDate = tradeDate,
                currentPositions = listOf("000001.SZ"),
                newlySelected = listOf("000002.SZ")
            ),
            nextSelections = listOf(selection("000001.SZ", 0.61), selection("000002.SZ", 0.88))
        )
        val runtime = PostMarketStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = dataSource
        )

        val result = runtime.rebuildDate(tradeDate, "test")

        assertTrue(result.accepted)
        assertEquals(listOf(listOf(tradeDate)), dataSource.executedDates)

        val envelope = assertNotNull(hub.current(StrategyTopic.POSITIONS))
        val payload = json.decodeFromJsonElement(StrategyPositionSnapshot.serializer(), envelope.payload)
        assertEquals("2026-04-30", payload.tradeDate)
        assertEquals(PositionSource.DAILY_AUDIT_COMPLETE, payload.source)
        assertEquals(listOf("000001.SZ"), payload.currentPositions)
        assertEquals(listOf("000002.SZ", "000001.SZ"), payload.nextSessionSelections)
        assertEquals(listOf(0.88, 0.61), payload.nextSessionSelectionDetails.map { it.modelScore })
        assertEquals(listOf("000002.SZ"), payload.newlySelected)
    }

    @Test
    fun `rebuild range uses open dates in order`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val nextDate = LocalDate(2026, 5, 6)
        val dataSource = FakePostMarketDataSource(
            openDates = listOf(tradeDate, nextDate),
            executionResult = PostMarketOrchestrator.ExecutionResult(processedDates = listOf(tradeDate, nextDate)),
            auditSummary = auditSummary(tradeDate = nextDate, currentPositions = listOf("000003.SZ")),
            nextSelections = listOf(selection("000003.SZ", 0.7))
        )
        val runtime = PostMarketStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = dataSource
        )

        val result = runtime.rebuildRange(tradeDate, nextDate, "test-range")

        assertTrue(result.accepted)
        assertEquals(listOf(listOf(tradeDate, nextDate)), dataSource.executedDates)
        val payload = json.decodeFromJsonElement(
            StrategyPositionSnapshot.serializer(),
            assertNotNull(hub.current(StrategyTopic.POSITIONS)).payload
        )
        assertEquals("2026-05-06", payload.tradeDate)
    }

    @Test
    fun `daily position snapshot derives newly selected from next session selections`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val dataSource = FakePostMarketDataSource(
            executionResult = PostMarketOrchestrator.ExecutionResult(processedDates = emptyList()),
            auditSummary = auditSummary(
                tradeDate = tradeDate,
                currentPositions = listOf("000001.SZ", "000002.SZ"),
                newlySelected = listOf("000002.SZ")
            ),
            nextSelections = listOf(selection("000001.SZ", 0.62), selection("000003.SZ", 0.93))
        )
        val runtime = PostMarketStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = dataSource
        )

        val result = runtime.publishLatestPositions("newly-selected-semantics")

        assertTrue(result.accepted)
        val payload = json.decodeFromJsonElement(
            StrategyPositionSnapshot.serializer(),
            assertNotNull(hub.current(StrategyTopic.POSITIONS)).payload
        )
        assertEquals(listOf("000003.SZ", "000001.SZ"), payload.nextSessionSelections)
        assertEquals(listOf(0.93, 0.62), payload.nextSessionSelectionDetails.map { it.modelScore })
        assertEquals(listOf("000003.SZ"), payload.newlySelected)
    }

    @Test
    fun `rebuild failure returns rejected result and does not publish positions`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val dataSource = FakePostMarketDataSource(
            executionResult = PostMarketOrchestrator.ExecutionResult(
                processedDates = emptyList(),
                failedDate = tradeDate,
                failure = IllegalStateException("daily facts missing")
            )
        )
        val runtime = PostMarketStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = dataSource
        )

        val result = runtime.rebuildDate(tradeDate, "test-fail")

        assertEquals(false, result.accepted)
        assertEquals(null, hub.current(StrategyTopic.POSITIONS))
    }

    @Test
    fun `rebuild range large date span processes all dates in single batch`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val dates = (1..15).map { day ->
            LocalDate(2026, 4, if (day <= 30) day else day - 30)
        }
        val latestDate = dates.last()
        val dataSource = FakePostMarketDataSource(
            openDates = dates,
            executionResult = PostMarketOrchestrator.ExecutionResult(processedDates = dates),
            auditSummary = auditSummary(
                tradeDate = latestDate,
                currentPositions = listOf("000001.SZ", "000002.SZ", "000003.SZ"),
                newlySelected = listOf("000004.SZ")
            ),
            nextSelections = listOf(
                selection("000001.SZ", 0.99),
                selection("000002.SZ", 0.88),
                selection("000003.SZ", 0.77),
                selection("000004.SZ", 0.66)
            )
        )
        val runtime = PostMarketStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = dataSource
        )

        val result = runtime.rebuildRange(dates.first(), dates.last(), "stress-test")

        assertTrue(result.accepted)
        assertEquals(1, dataSource.executedDates.size)
        assertEquals(dates, dataSource.executedDates.first())

        val payload = json.decodeFromJsonElement(
            StrategyPositionSnapshot.serializer(),
            assertNotNull(hub.current(StrategyTopic.POSITIONS)).payload
        )
        assertEquals(latestDate.toString(), payload.tradeDate)
        assertEquals(listOf("000001.SZ", "000002.SZ", "000003.SZ"), payload.currentPositions)
        assertEquals(listOf("000001.SZ", "000002.SZ", "000003.SZ", "000004.SZ"), payload.nextSessionSelections)
        assertEquals(listOf("000004.SZ"), payload.newlySelected)
    }

    @Test
    fun `rebuild range with inverted dates returns rejected result`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val dataSource = FakePostMarketDataSource(
            executionResult = PostMarketOrchestrator.ExecutionResult(processedDates = emptyList())
        )
        val runtime = PostMarketStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = dataSource
        )

        val result = runtime.rebuildRange(
            LocalDate(2026, 5, 6),
            LocalDate(2026, 4, 30),
            "inverted-test"
        )

        assertEquals(false, result.accepted)
        assertTrue(result.message.contains("invalid rebuild range"))
        assertEquals(0, dataSource.executedDates.size)
    }

    @Test
    fun `catch up republishes positions when db audit is newer than memory snapshot`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val olderDate = LocalDate(2026, 4, 30)
        val newerDate = LocalDate(2026, 5, 6)
        val dataSource = FakePostMarketDataSource(
            executionResult = PostMarketOrchestrator.ExecutionResult(processedDates = emptyList()),
            auditSummary = auditSummary(tradeDate = olderDate, currentPositions = listOf("000001.SZ")),
            nextSelections = listOf(selection("000001.SZ", 0.7))
        )
        val runtime = PostMarketStrategyRuntime(snapshotHub = hub, json = json, dataSource = dataSource)

        // 内存先 hydrate 到旧日
        runtime.publishLatestPositions("startup")
        assertEquals(olderDate.toString(), latestPositionsTradeDate(hub))

        // 模拟新审计落库（DB 最新审计日推进），追平应重新发布到新日
        dataSource.auditSummary = auditSummary(tradeDate = newerDate, currentPositions = listOf("000002.SZ"))
        val caughtUp = runtime.catchUpLatestPositionsIfStale()

        assertEquals(newerDate, caughtUp)
        assertEquals(newerDate.toString(), latestPositionsTradeDate(hub))
    }

    @Test
    fun `catch up is no-op when db audit equals memory snapshot`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val dataSource = FakePostMarketDataSource(
            executionResult = PostMarketOrchestrator.ExecutionResult(processedDates = emptyList()),
            auditSummary = auditSummary(tradeDate = tradeDate, currentPositions = listOf("000001.SZ")),
            nextSelections = listOf(selection("000001.SZ", 0.7))
        )
        val runtime = PostMarketStrategyRuntime(snapshotHub = hub, json = json, dataSource = dataSource)

        runtime.publishLatestPositions("startup")
        val versionAfterHydration = assertNotNull(hub.current(StrategyTopic.POSITIONS)).version

        // DB 审计日未变,追平应不发布(返回 null),快照版本不变
        val caughtUp = runtime.catchUpLatestPositionsIfStale()

        assertEquals(null, caughtUp)
        assertEquals(versionAfterHydration, assertNotNull(hub.current(StrategyTopic.POSITIONS)).version)
    }

    private suspend fun latestPositionsTradeDate(
        hub: LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>
    ): String = json.decodeFromJsonElement(
        StrategyPositionSnapshot.serializer(),
        assertNotNull(hub.current(StrategyTopic.POSITIONS)).payload
    ).tradeDate

    private fun selection(tsCode: String, modelScore: Double) = ProfitPredictionSelection(
        tradeDate = tradeDate,
        targetDate = tradeDate,
        tsCode = tsCode,
        modelScore = modelScore,
        selected = true,
        targetWeight = 0.2,
        sentimentExposure = 1.0,
        selectionReason = null,
        modelId = "test-model",
        candidateMode = "all-universe"
    )

    private fun auditSummary(
        tradeDate: LocalDate,
        currentPositions: List<String>,
        newlySelected: List<String> = emptyList()
    ) = StrategyAuditSummary(
        tradeDate = tradeDate,
        universeSize = 3,
        signalPositiveCount = 2,
        selectedCount = 2,
        emptyReason = null,
        newlySelected = newlySelected,
        dropped = emptyList(),
        currentPositions = currentPositions,
        sentimentExposure = 1.0,
        bullRatio = 0.6,
        marketVol = 0.02,
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
}

private class FakePostMarketDataSource(
    private val openDates: List<LocalDate> = emptyList(),
    private val executionResult: PostMarketOrchestrator.ExecutionResult,
    // var: 审计追平测试需在发布后改变 DB 最新审计日,模拟新审计落库
    var auditSummary: StrategyAuditSummary? = null,
    private val currentPositions: List<String> = emptyList(),
    private val nextSelections: List<ProfitPredictionSelection> = emptyList()
) : PostMarketStrategyRuntimeDataSource {
    val executedDates = mutableListOf<List<LocalDate>>()

    override fun findOpenDates(startDate: LocalDate, endDate: LocalDate): List<LocalDate> = openDates

    override suspend fun executeTradeDates(tradeDates: List<LocalDate>): PostMarketOrchestrator.ExecutionResult {
        executedDates += tradeDates
        return executionResult
    }

    override fun loadLatestAuditSummary(): StrategyAuditSummary? = auditSummary

    override fun loadAuditSummary(tradeDate: LocalDate): StrategyAuditSummary? =
        auditSummary?.takeIf { it.tradeDate == tradeDate }

    override fun loadCurrentPositionCodes(tradeDate: LocalDate): List<String> = currentPositions

    override fun loadNextSessionSelections(tradeDate: LocalDate): List<ProfitPredictionSelection> =
        nextSelections.sortedWith(compareByDescending<ProfitPredictionSelection> { it.modelScore }.thenBy { it.tsCode })
}
