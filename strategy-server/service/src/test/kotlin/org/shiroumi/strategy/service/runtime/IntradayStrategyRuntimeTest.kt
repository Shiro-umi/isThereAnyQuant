package org.shiroumi.strategy.service.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import model.Candle
import model.dataprovider.SentimentRuntimeSeed
import model.dataprovider.SentimentSymbolStateSeed
import model.ws.IntradaySnapshotPayload
import model.ws.StrategyPositionSnapshot
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.core.daily.FactorRollingState
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.StockFactorSnapshot
import org.shiroumi.strategy.core.daily.TargetPosition
import org.shiroumi.strategy.service.model.ProfitPredictionTargetSelector
import kotlin.uuid.ExperimentalUuidApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalUuidApi::class)
class IntradayStrategyRuntimeTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val tradeDate = LocalDate(2026, 4, 30)
    private val previousDate = LocalDate(2026, 4, 29)

    @Test
    fun `refresh publishes intraday and positions snapshots from service runtime`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val runtime = IntradayStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = FakeRuntimeDataSource(
                tradeDate = tradeDate,
                previousDate = previousDate,
                historicalSentiments = listOf(sentiment(tradeDate, reason = "historical")),
                factors = listOf(
                    factor("000001.SZ", rankScore = 3.0),
                    factor("000002.SZ", rankScore = 2.0),
                    factor("000003.SZ", rankScore = 1.0)
                ),
                currentPositions = listOf("000002.SZ"),
                postMarketTargets = listOf(
                    target("000001.SZ", 0.93),
                    target("000002.SZ", 0.91),
                    target("000003.SZ", 0.89),
                )
            ),
            intradayModelSelectionEnabled = false,
        )

        val result = runtime.refresh("test")

        assertTrue(result.accepted)
        assertEquals(1, result.intradayEnvelope?.version)
        assertEquals(1, result.positionsEnvelope?.version)

        val intraday = assertNotNull(hub.current(StrategyTopic.INTRADAY))
        val payload = json.decodeFromJsonElement(IntradaySnapshotPayload.serializer(), intraday.payload)
        assertEquals("2026-04-30", payload.sentiment.tradeDate)
        assertEquals(listOf("000001.SZ", "000002.SZ", "000003.SZ"), payload.portfolio.map { it.tsCode })
        assertEquals(3, payload.calcMetrics.stockCount)

        val positions = assertNotNull(hub.current(StrategyTopic.POSITIONS))
        val positionPayload = json.decodeFromJsonElement(StrategyPositionSnapshot.serializer(), positions.payload)
        assertEquals(listOf("000002.SZ"), positionPayload.currentPositions)
        assertEquals(listOf("000001.SZ", "000003.SZ"), positionPayload.newlySelected)
    }

    @Test
    fun `missing sentiment seed falls back to historical sentiment without blocking snapshot`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val runtime = IntradayStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = FakeRuntimeDataSource(
                tradeDate = tradeDate,
                previousDate = previousDate,
                historicalSentiments = listOf(sentiment(tradeDate, reason = "historical-fallback")),
                factors = listOf(factor("000001.SZ", rankScore = 1.0))
            ),
            intradayModelSelectionEnabled = false,
        )

        val result = runtime.refresh("missing-seed")

        assertTrue(result.accepted)
        val payload = json.decodeFromJsonElement(
            IntradaySnapshotPayload.serializer(),
            assertNotNull(hub.current(StrategyTopic.INTRADAY)).payload
        )
        assertEquals("historical-fallback", payload.sentiment.reason)
    }

    @Test
    fun `hfq realtime factor with missing first adj keeps historical factor`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val runtime = IntradayStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = FakeRuntimeDataSource(
                tradeDate = tradeDate,
                previousDate = previousDate,
                historicalSentiments = listOf(sentiment(tradeDate, reason = "historical")),
                factors = listOf(factor("000001.SZ", close = 10.0, rankScore = 1.0)),
                states = listOf(factorState("000001.SZ")),
                realtimeCandles = mapOf("000001.SZ" to candle("000001.SZ", close = 20f)),
                firstAdj = emptyMap()
            ),
            intradayModelSelectionEnabled = false,
        )

        val result = runtime.refresh("missing-first-adj")

        assertTrue(result.accepted)
        val payload = json.decodeFromJsonElement(
            IntradaySnapshotPayload.serializer(),
            assertNotNull(hub.current(StrategyTopic.INTRADAY)).payload
        )
        assertEquals(10.0, payload.topStocks.first().close)
    }

    @Test
    fun `qfq sentiment uses prepared bar normalized close from realtime candle`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val runtime = IntradayStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = FakeRuntimeDataSource(
                tradeDate = tradeDate,
                previousDate = previousDate,
                historicalSentiments = listOf(sentiment(previousDate, reason = "historical")),
                factors = listOf(factor("000001.SZ", rankScore = 1.0)),
                seed = sentimentSeed(
                    signalBasis = "QFQ",
                    sampleCodes = listOf("000001.SZ", "000002.SZ")
                ),
                realtimeCandles = mapOf(
                    "000001.SZ" to candle("000001.SZ", close = 12f, adj = 3f),
                    "000002.SZ" to candle("000002.SZ", close = 6f, adj = 2f)
                ),
                adjByTradeDate = mapOf(
                    previousDate to mapOf(
                        "000001.SZ" to 2f,
                        "000002.SZ" to 2f
                    )
                )
            ),
            intradayModelSelectionEnabled = false,
        )

        val result = runtime.refresh("qfq-sentiment")

        assertTrue(result.accepted)
        val payload = json.decodeFromJsonElement(
            IntradaySnapshotPayload.serializer(),
            assertNotNull(hub.current(StrategyTopic.INTRADAY)).payload
        )
        assertEquals("2026-04-30", payload.sentiment.tradeDate)
        assertEquals("QFQ", payload.sentiment.signalBasis)
        assertEquals(0.5, payload.sentiment.bullRatio)
    }

    @Test
    fun `qfq realtime factor uses prepared bar normalized prices`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val runtime = IntradayStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = FakeRuntimeDataSource(
                tradeDate = tradeDate,
                previousDate = previousDate,
                historicalSentiments = listOf(sentiment(tradeDate, reason = "historical")),
                factors = listOf(factor("000001.SZ", close = 10.0, rankScore = 1.0, basis = "QFQ")),
                states = listOf(factorState("000001.SZ", basis = "QFQ")),
                realtimeCandles = mapOf("000001.SZ" to candle("000001.SZ", close = 12f, adj = 3f)),
                adjByTradeDate = mapOf(previousDate to mapOf("000001.SZ" to 2f))
            ),
            intradayModelSelectionEnabled = false,
        )

        val result = runtime.refresh("qfq-factor")

        assertTrue(result.accepted)
        val payload = json.decodeFromJsonElement(
            IntradaySnapshotPayload.serializer(),
            assertNotNull(hub.current(StrategyTopic.INTRADAY)).payload
        )
        assertEquals(18.0, payload.topStocks.first().close)
        assertEquals(28.5, payload.topStocks.first().open)
    }

    @Test
    fun `intraday model selection overrides post market targets in runtime snapshot`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val runtime = IntradayStrategyRuntime(
            snapshotHub = hub,
            json = json,
            dataSource = FakeRuntimeDataSource(
                tradeDate = tradeDate,
                previousDate = previousDate,
                historicalSentiments = listOf(sentiment(tradeDate, reason = "historical")),
                factors = listOf(
                    factor("000001.SZ", rankScore = 3.0),
                    factor("000002.SZ", rankScore = 2.0),
                    factor("000003.SZ", rankScore = 1.0)
                ),
                realtimeCandles = mapOf(
                    "000001.SZ" to candle("000001.SZ", close = 11f),
                    "000002.SZ" to candle("000002.SZ", close = 12f),
                    "000003.SZ" to candle("000003.SZ", close = 13f),
                ),
                postMarketTargets = listOf(target("000001.SZ", 0.93))
            ),
            profitPredictionSelector = FixedProfitPredictionTargetSelector(
                intradayTargets = listOf(
                    target("000003.SZ", 0.97),
                    target("000002.SZ", 0.95),
                )
            ),
            intradayModelSelectionEnabled = true,
        )

        val result = runtime.refresh("intraday-model")

        assertTrue(result.accepted)
        val payload = json.decodeFromJsonElement(
            IntradaySnapshotPayload.serializer(),
            assertNotNull(hub.current(StrategyTopic.INTRADAY)).payload
        )
        assertEquals(listOf("000003.SZ", "000002.SZ"), payload.portfolio.map { it.tsCode })
    }

    private fun sentiment(
        tradeDate: LocalDate,
        reason: String
    ) = MarketSentimentSnapshot(
        tradeDate = tradeDate,
        signalBasis = "HFQ",
        sampleSize = 3,
        bullRatio = 0.6,
        fftScore = 0.5,
        residualScore = 0.5,
        marketVol = 0.02,
        volZ = 0.0,
        accelZ = 0.0,
        sentimentExposure = 1.0,
        ratioNorm = 0.5,
        volScore = 0.5,
        accelScore = 0.5,
        absoluteFloor = 0.256,
        volCap = 1.0,
        sufficientHistory = true,
        requiredHistory = 252,
        reason = reason
    )

    private fun factor(
        tsCode: String,
        close: Double = 10.0,
        rankScore: Double,
        basis: String = "HFQ"
    ) = StockFactorSnapshot(
        tradeDate = previousDate,
        tsCode = tsCode,
        signalBasis = basis,
        executionBasis = basis,
        sufficientHistory = true,
        requiredHistory = 60,
        open = 9.5,
        high = 10.5,
        low = 9.0,
        close = close,
        volume = 1000.0,
        executionOpen = 9.5,
        executionClose = close,
        hfqFactor = 1.0,
        ema10 = 9.8,
        ema30 = 9.0,
        emaBull = true,
        atr14 = 0.5,
        signal = true,
        momentum20 = 0.1,
        volRatio520 = 1.2,
        amomCombined = 0.2,
        rankScore = rankScore
    )

    private fun factorState(
        tsCode: String,
        basis: String = "HFQ"
    ) = FactorRollingState(
        tradeDate = previousDate,
        tsCode = tsCode,
        signalBasis = basis,
        executionBasis = basis,
        requiredHistory = 60,
        barsCount = 60,
        emaShort = 10.0,
        emaLong = 9.0,
        atr = 0.5,
        holding = true,
        stopPrice = 9.0,
        holdingDays = 3,
        shortVolumeSum = 5000.0,
        longVolumeSum = 20_000.0,
        prevClose = 10.0,
        recentReturns = List(20) { 0.01 },
        recentCloses = List(20) { 10.0 },
        recentVolumes = List(20) { 1000.0 },
        momentumBaseClose = 9.0
    )

    private fun candle(
        tsCode: String,
        close: Float,
        adj: Float = 2f
    ) = Candle(
        tsCode = tsCode,
        date = tradeDate,
        open = 19f,
        high = 21f,
        low = 18f,
        close = close,
        adj = adj,
        volume = 1200f,
        turnoverReal = 0f,
        pe = 0f,
        peTtm = 0f,
        pb = 0f,
        ps = 0f,
        psTtm = 0f,
        mvTotal = 0f,
        mvCirc = 0f
    )

    private fun sentimentSeed(
        signalBasis: String,
        sampleCodes: List<String>
    ) = SentimentRuntimeSeed(
        scope = "main_board",
        forTradeDate = tradeDate,
        sourceTradeDate = previousDate,
        signalBasis = signalBasis,
        requiredHistory = 20,
        sampleSize = sampleCodes.size,
        sampleCodes = sampleCodes,
        symbolStates = sampleCodes.map { tsCode ->
            SentimentSymbolStateSeed(
                tsCode = tsCode,
                emaShort = 10.0,
                emaLong = 9.5,
                prevClose = 10.0,
                recentReturns = List(20) { 0.0 },
                nextReturnIndex = 0,
                returnWindowSize = 20,
                returnSum = 0.0,
                returnSumSq = 0.0
            )
        },
        bullRatioHistory = List(20) { 0.5 },
        marketVolHistory = List(20) { 0.01 },
        accelHistory = List(20) { 0.0 },
        combinedHistory = List(20) { 0.5 },
        totalDays = 20
    )

    private fun target(tsCode: String, score: Double) = TargetPosition(
        tradeDate = previousDate,
        targetDate = tradeDate,
        tsCode = tsCode,
        selectionScore = score,
        selected = true,
        targetWeight = 1.0 / 3.0,
        sentimentExposure = 1.0,
        selectionReason = "profit-prediction-7pct:test:Top3",
    )
}

private class FakeRuntimeDataSource(
    private val tradeDate: LocalDate,
    private val previousDate: LocalDate,
    private val historicalSentiments: List<MarketSentimentSnapshot>,
    private val factors: List<StockFactorSnapshot>,
    private val seed: SentimentRuntimeSeed? = null,
    private val states: List<FactorRollingState> = emptyList(),
    private val realtimeCandles: Map<String, Candle> = emptyMap(),
    private val firstAdj: Map<String, Float> = emptyMap(),
    private val adjByTradeDate: Map<LocalDate, Map<String, Float>> = emptyMap(),
    private val currentPositions: List<String> = emptyList(),
    private val postMarketTargets: List<TargetPosition> = emptyList()
) : IntradayStrategyRuntimeDataSource {
    override fun resolveTradeDate(): LocalDate = tradeDate
    override fun loadActiveUniverse(): List<String> = factors.map { it.tsCode }
    override fun loadRecentSentiments(endDateInclusive: LocalDate, limit: Int): List<MarketSentimentSnapshot> =
        historicalSentiments
    override fun loadSentimentSeed(scope: String, tradeDate: LocalDate): SentimentRuntimeSeed? = seed
    override fun loadFirstAdj(tsCodes: List<String>): Map<String, Float> = firstAdj
    override fun loadAdjByTradeDate(tradeDate: LocalDate, tsCodes: List<String>): Map<String, Float> =
        adjByTradeDate[tradeDate].orEmpty().filterKeys { it in tsCodes }
    override suspend fun prefetchRealtimeDailyCandles(tsCodes: List<String>, tradeDate: LocalDate) = Unit
    override suspend fun loadRealtimeDailyCandles(tsCodes: List<String>, tradeDate: LocalDate): Map<String, Candle> =
        realtimeCandles.filterKeys { it in tsCodes }
    override fun findPreviousTradingDate(tradeDate: LocalDate): LocalDate? = previousDate
    override fun loadStockFactorsByDate(tradeDate: LocalDate, tsCodes: List<String>): List<StockFactorSnapshot> =
        factors.filter { it.tsCode in tsCodes }
    override fun loadLatestStockFactorsBefore(
        tradeDate: LocalDate,
        tsCodes: List<String>
    ): Pair<LocalDate, List<StockFactorSnapshot>>? = previousDate to factors.filter { it.tsCode in tsCodes }
    override fun loadFactorStates(tradeDate: LocalDate, tsCodes: List<String>): List<FactorRollingState> =
        states.filter { it.tsCode in tsCodes }
    override fun loadCurrentPositionCodes(tradeDate: LocalDate): List<String> = currentPositions
    override fun loadPostMarketTargetPositions(targetDate: LocalDate): List<TargetPosition> =
        postMarketTargets.filter { it.targetDate == targetDate }
    override fun loadSentimentByDate(tradeDate: LocalDate): MarketSentimentSnapshot? =
        historicalSentiments.firstOrNull { it.tradeDate == tradeDate }
}

private class FixedProfitPredictionTargetSelector(
    private val intradayTargets: List<TargetPosition>
) : ProfitPredictionTargetSelector {
    override suspend fun generateTargets(
        tradeDate: LocalDate,
        targetDate: LocalDate,
        universeSymbols: List<String>,
        sentiment: MarketSentimentSnapshot,
    ): List<TargetPosition> = emptyList()

    override suspend fun generateIntradayTargets(
        tradeDate: LocalDate,
        universeSymbols: List<String>,
        realtimeDailyCandles: Map<String, Candle>,
        sentiment: MarketSentimentSnapshot,
    ): List<TargetPosition> = intradayTargets
}
