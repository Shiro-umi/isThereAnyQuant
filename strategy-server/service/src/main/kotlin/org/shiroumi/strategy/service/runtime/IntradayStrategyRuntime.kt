package org.shiroumi.strategy.service.runtime

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import model.Candle
import model.PriceBasis
import model.dataprovider.SentimentRuntimeSeed
import model.ws.CalcMetrics
import model.ws.IntradaySnapshotPayload
import model.ws.MarketSentimentSnapshot
import model.ws.PositionSource
import model.ws.StrategySelectionSnapshot
import model.ws.StockFactorSnapshot
import model.ws.StrategyPositionSnapshot
import model.ws.TargetPosition
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.database.stock.StockReader
import org.shiroumi.database.strategy.daily.repository.DailyFactorRollingStateRepository
import org.shiroumi.database.strategy.daily.repository.DailyMarketSentimentRepository
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.database.strategy.daily.repository.DailyStockFactorRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyHoldingRepository
import org.shiroumi.database.strategy.daily.repository.SentimentRuntimeSeedRepository
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedBar
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.core.daily.FactorRollingState
import org.shiroumi.strategy.core.daily.MarketSentimentCalculator
import org.shiroumi.strategy.core.daily.StockFactorCalculator
import org.shiroumi.strategy.core.daily.preprocessing.PreparedBarFactory
import org.shiroumi.strategy.core.daily.seed.toRollingState
import org.shiroumi.strategy.core.intraday.IntradayPortfolioGenerator
import org.shiroumi.strategy.service.universe.MainBoardUniverseProvider
import utils.logger
import kotlin.uuid.ExperimentalUuidApi
import org.shiroumi.strategy.core.daily.TargetPosition as DomainTargetPosition
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot as DomainSentimentSnapshot
import org.shiroumi.strategy.core.daily.StockFactorSnapshot as DomainFactorSnapshot
import org.shiroumi.strategy.core.intraday.TargetPosition as DomainIntradayPosition

class IntradayStrategyRuntime(
    private val snapshotHub: LocalStrategySnapshotHub<JsonElement>,
    private val json: Json,
    private val scope: String = MainBoardUniverseProvider.UNIVERSE_TYPE,
    private val dataSource: IntradayStrategyRuntimeDataSource,
) : IntradayRuntime {
    private val logger by logger("IntradayStrategyRuntime")

    override suspend fun refresh(reason: String): IntradayRefreshResult {
        val startedAt = System.currentTimeMillis()
        val tradeDate = dataSource.resolveTradeDate()
        val universe = dataSource.loadActiveUniverse()
        if (universe.isEmpty()) {
            return IntradayRefreshResult(false, "active universe is empty")
        }

        dataSource.prefetchRealtimeDailyCandles(universe, tradeDate)

        val historicalSentiments = dataSource.loadRecentSentiments(
            endDateInclusive = tradeDate,
            limit = 252
        )
        val sentiment = buildSentiment(tradeDate, historicalSentiments)
            ?: return IntradayRefreshResult(false, "no historical sentiment or runtime seed for $tradeDate")

        val factors = buildMergedFactors(tradeDate, universe)
        if (factors.isEmpty()) {
            return IntradayRefreshResult(false, "no factor baseline for $tradeDate")
        }

        val intradayPayload = buildIntradayPayload(
            tradeDate = tradeDate,
            sentiment = sentiment,
            factors = factors,
            totalMs = System.currentTimeMillis() - startedAt
        )
        val intradayEnvelope = snapshotHub.publish(
            StrategyTopic.INTRADAY,
            json.encodeToJsonElement(IntradaySnapshotPayload.serializer(), intradayPayload)
        )
        val positionSnapshot = buildPositionSnapshot(tradeDate, intradayPayload)
        val positionsEnvelope = snapshotHub.publish(
            StrategyTopic.POSITIONS,
            json.encodeToJsonElement(
                StrategyPositionSnapshot.serializer(),
                positionSnapshot
            )
        )
        // 盘中只发布 INTRADAY / POSITIONS 投影。持仓跟踪时间线（POSITION_TRACKING）只由盘后确认链路
        // 与最早跟随日校准生成，盘中不再覆盖/追加实时日，避免当日盘后确认列被盘中投影顶替。

        logger.info(
            "strategy-service intraday refreshed reason=$reason tradeDate=$tradeDate " +
                "factors=${factors.size} portfolio=${intradayPayload.portfolio.size} " +
                "intradayVersion=${intradayEnvelope.version} positionsVersion=${positionsEnvelope.version}"
        )
        return IntradayRefreshResult(
            accepted = true,
            message = "intraday snapshot refreshed tradeDate=$tradeDate",
            intradayEnvelope = intradayEnvelope,
            positionsEnvelope = positionsEnvelope
        )
    }

    private suspend fun buildSentiment(
        tradeDate: LocalDate,
        historical: List<DomainSentimentSnapshot>
    ): DomainSentimentSnapshot? {
        val seed = dataSource.loadSentimentSeed(scope, tradeDate)
            ?: return historical.lastOrNull()
        val baseState = runCatching { seed.toRollingState() }.getOrElse { error ->
            logger.warning("runtime seed decode failed tradeDate=$tradeDate reason=${error.message}")
            return historical.lastOrNull()
        }
        val baseSentiment = historical.lastOrNull { it.tradeDate == seed.sourceTradeDate }
            ?: dataSource.loadSentimentByDate(seed.sourceTradeDate)
            ?: return historical.lastOrNull()
        val signalBasis = runCatching { PriceBasis.valueOf(seed.signalBasis) }.getOrNull()
            ?: return historical.lastOrNull()

        val firstAdj = if (signalBasis == PriceBasis.HFQ) {
            dataSource.loadFirstAdj(baseState.sampleCodes)
        } else {
            emptyMap()
        }
        val sourceAdj = if (signalBasis == PriceBasis.QFQ) {
            dataSource.loadAdjByTradeDate(seed.sourceTradeDate, baseState.sampleCodes)
        } else {
            emptyMap()
        }
        val realtimeByCode = dataSource.loadRealtimeDailyCandles(
            tsCodes = baseState.sampleCodes,
            tradeDate = tradeDate
        )
        val observed = baseState.sampleCodes.mapNotNull { tsCode ->
            val candle = realtimeByCode[tsCode] ?: return@mapNotNull null
            val bar = buildSentimentPreparedBar(
                candle = candle,
                signalBasis = signalBasis,
                firstAdj = firstAdj[tsCode],
                sourceTradeDateAdj = sourceAdj[tsCode]
            ) ?: return@mapNotNull null
            tsCode to bar.close
        }.toMap()
        if (observed.isEmpty()) {
            return baseSentiment
        }
        return runCatching {
            MarketSentimentCalculator.calculateStrict(
                state = baseState,
                observedClosesBySymbol = observed,
                tradeDate = tradeDate,
                requiredHistory = seed.requiredHistory
            ).second
        }.getOrElse { error ->
            logger.warning("intraday sentiment calculation failed tradeDate=$tradeDate reason=${error.message}")
            baseSentiment
        }
    }

    private suspend fun buildMergedFactors(
        tradeDate: LocalDate,
        universe: List<String>
    ): List<DomainFactorSnapshot> {
        val previousTradeDate = dataSource.findPreviousTradingDate(tradeDate)
        val baseline = previousTradeDate
            ?.let { date -> dataSource.loadStockFactorsByDate(date, universe).let { date to it } }
            ?.takeIf { it.second.isNotEmpty() }
            ?: dataSource.loadLatestStockFactorsBefore(tradeDate, universe)
            ?: return emptyList()
        val baselineDate = baseline.first
        val historical = baseline.second
        val states = dataSource.loadFactorStates(
            tradeDate = baselineDate,
            tsCodes = historical.map { it.tsCode }
        ).associateBy { it.tsCode }
        if (states.isEmpty()) return historical

        val realtimeByCode = dataSource.loadRealtimeDailyCandles(
            tsCodes = historical.map { it.tsCode },
            tradeDate = tradeDate
        )
        if (realtimeByCode.isEmpty()) return historical

        val firstAdj = dataSource.loadFirstAdj(
            states.values
                .filter { it.requiresFirstAdj() }
                .map { it.tsCode }
        )
        val qfqSourceAdj = dataSource.loadAdjByTradeDate(
            tradeDate = baselineDate,
            tsCodes = states.values
                .filter { it.requiresQfqSourceAdj() }
                .map { it.tsCode }
        )
        val realtime = historical.mapNotNull { factor ->
            val state = states[factor.tsCode] ?: return@mapNotNull null
            val candle = realtimeByCode[factor.tsCode] ?: return@mapNotNull null
            val bar = buildRealtimePreparedBar(
                candle = candle,
                state = state,
                firstAdj = firstAdj[factor.tsCode],
                sourceTradeDateAdj = qfqSourceAdj[factor.tsCode]
            ) ?: return@mapNotNull null
            StockFactorCalculator.calculate(state, bar)?.second
        }
        if (realtime.isEmpty()) return historical
        val realtimeByTsCode = realtime.associateBy { it.tsCode }
        return historical.map { factor -> realtimeByTsCode[factor.tsCode] ?: factor }
    }

    private fun FactorRollingState.requiresFirstAdj(): Boolean =
        signalBasis.toPriceBasisOrNull() == PriceBasis.HFQ ||
            executionBasis.toPriceBasisOrNull() == PriceBasis.HFQ

    private fun FactorRollingState.requiresQfqSourceAdj(): Boolean =
        signalBasis.toPriceBasisOrNull() == PriceBasis.QFQ ||
            executionBasis.toPriceBasisOrNull() == PriceBasis.QFQ

    private fun buildSentimentPreparedBar(
        candle: Candle,
        signalBasis: PriceBasis,
        firstAdj: Float?,
        sourceTradeDateAdj: Float?
    ): PreparedBar? =
        buildPreparedBar(
            candle = candle,
            signalBasis = signalBasis,
            executionBasis = PriceBasis.RAW,
            firstAdj = firstAdj,
            sourceTradeDateAdj = sourceTradeDateAdj
        )

    private fun buildRealtimePreparedBar(
        candle: Candle,
        state: FactorRollingState,
        firstAdj: Float?,
        sourceTradeDateAdj: Float?
    ): PreparedBar? {
        val signalBasis = state.signalBasis.toPriceBasisOrNull() ?: return null
        val executionBasis = state.executionBasis.toPriceBasisOrNull() ?: return null
        return buildPreparedBar(
            candle = candle,
            signalBasis = signalBasis,
            executionBasis = executionBasis,
            firstAdj = firstAdj,
            sourceTradeDateAdj = sourceTradeDateAdj
        )
    }

    private fun buildPreparedBar(
        candle: Candle,
        signalBasis: PriceBasis,
        executionBasis: PriceBasis,
        firstAdj: Float?,
        sourceTradeDateAdj: Float?
    ): PreparedBar? {
        val normalizedFirstAdj = if (signalBasis == PriceBasis.HFQ || executionBasis == PriceBasis.HFQ) {
            if (candle.adj <= 0f) return null
            firstAdj?.takeIf { it > 0f } ?: return null
        } else {
            1f
        }
        val preparedCandle = candle.withRuntimeQfq(
            sourceTradeDateAdj = sourceTradeDateAdj,
            required = signalBasis == PriceBasis.QFQ || executionBasis == PriceBasis.QFQ
        ) ?: return null
        return PreparedBarFactory.fromCandle(
            candle = preparedCandle,
            normalizedFirstAdj = normalizedFirstAdj,
            signalBasis = signalBasis,
            executionBasis = executionBasis
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun Candle.withRuntimeQfq(
        sourceTradeDateAdj: Float?,
        required: Boolean
    ): Candle? {
        if (!required) return this
        val todayAdj = adj.takeIf { it > 0f } ?: return null
        val sourceAdj = sourceTradeDateAdj?.takeIf { it > 0f } ?: return null
        val priceFactor = todayAdj / sourceAdj
        val volumeFactor = sourceAdj / todayAdj
        return copy(
            openQfq = open * priceFactor,
            highQfq = high * priceFactor,
            lowQfq = low * priceFactor,
            closeQfq = close * priceFactor,
            volumeQfq = volume * volumeFactor
        )
    }

    private fun buildIntradayPayload(
        tradeDate: LocalDate,
        sentiment: DomainSentimentSnapshot,
        factors: List<DomainFactorSnapshot>,
        totalMs: Long
    ): IntradaySnapshotPayload {
        // 盘中不再做独立模型选股投影，目标组合恒为盘后确认组合，
        // 盘中只用实时因子补充价格/量能展示。
        val postMarketTargets = dataSource.loadPostMarketTargetPositions(tradeDate)
        val portfolio = IntradayPortfolioGenerator.generate(
            tradeDate = tradeDate,
            timestamp = System.currentTimeMillis(),
            factors = factors,
            sentiment = sentiment,
            postMarketPositions = postMarketTargets,
        )
        return IntradaySnapshotPayload(
            timestamp = System.currentTimeMillis(),
            sentiment = sentiment.toWs(),
            portfolio = portfolio.map { it.toWs() },
            topStocks = factors.sortedByDescending { it.rankScore }.take(10).map { it.toWs() },
            calcMetrics = CalcMetrics(
                totalMs = totalMs,
                stockCount = factors.size
            )
        )
    }

    private fun buildPositionSnapshot(
        tradeDate: LocalDate,
        payload: IntradaySnapshotPayload
    ): StrategyPositionSnapshot {
        val selectedDetails = payload.portfolio
            .filter { it.selected }
            .sortedWith(compareByDescending<TargetPosition> { it.rankScore }.thenBy { it.tsCode })
            .map {
                StrategySelectionSnapshot(
                    tsCode = it.tsCode,
                    modelScore = it.rankScore,
                    limitPrice = it.limitPrice,
                )
            }
        val selectedCodes = selectedDetails.map { it.tsCode }
        val currentPositions = dataSource.loadCurrentPositionCodes(tradeDate)
        return StrategyPositionSnapshot(
            tradeDate = payload.sentiment.tradeDate,
            currentPositions = currentPositions,
            source = PositionSource.INTRADAY_REALTIME,
            nextSessionSelections = selectedCodes,
            nextSessionSelectionDetails = selectedDetails,
            newlySelected = selectedCodes.filterNot { it in currentPositions }
        )
    }

    private fun DomainSentimentSnapshot.toWs() = MarketSentimentSnapshot(
        tradeDate = tradeDate.toString(),
        signalBasis = signalBasis,
        sampleSize = sampleSize,
        bullRatio = bullRatio,
        fftScore = fftScore,
        residualScore = residualScore,
        marketVol = marketVol,
        volZ = volZ,
        accelZ = accelZ,
        sentimentExposure = sentimentExposure,
        ratioNorm = ratioNorm,
        volScore = volScore,
        accelScore = accelScore,
        absoluteFloor = absoluteFloor,
        volCap = volCap,
        sufficientHistory = sufficientHistory,
        requiredHistory = requiredHistory,
        reason = reason
    )

    private fun DomainFactorSnapshot.toWs() = StockFactorSnapshot(
        tradeDate = tradeDate.toString(),
        tsCode = tsCode,
        signalBasis = signalBasis,
        executionBasis = executionBasis,
        sufficientHistory = sufficientHistory,
        requiredHistory = requiredHistory,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        executionOpen = executionOpen,
        executionClose = executionClose,
        hfqFactor = hfqFactor,
        ema10 = ema10,
        ema30 = ema30,
        emaBull = emaBull,
        atr14 = atr14,
        signal = signal,
        momentum20 = momentum20,
        volRatio520 = volRatio520,
        amomCombined = amomCombined,
        rankScore = rankScore
    )

    private fun DomainIntradayPosition.toWs() = TargetPosition(
        tsCode = tsCode,
        name = null,
        rankScore = rankScore,
        selected = selected,
        targetWeight = targetWeight,
        sentimentExposure = sentimentExposure,
        priceChangePct = priceChangePct,
        volumeRatio = volumeRatio,
        postMarketSelected = postMarketSelected,
        postMarketWeight = postMarketWeight,
        action = action,
        actionReason = actionReason,
        limitPrice = limitPrice
    )

    private fun String.toPriceBasisOrNull(): PriceBasis? =
        runCatching { PriceBasis.valueOf(this) }.getOrNull()
}

data class IntradayRefreshResult(
    val accepted: Boolean,
    val message: String,
    val intradayEnvelope: StrategySnapshotEnvelope<JsonElement>? = null,
    val positionsEnvelope: StrategySnapshotEnvelope<JsonElement>? = null
)

interface IntradayStrategyRuntimeDataSource {
    fun resolveTradeDate(): LocalDate
    fun loadActiveUniverse(): List<String>
    fun loadRecentSentiments(endDateInclusive: LocalDate, limit: Int): List<DomainSentimentSnapshot>
    fun loadSentimentSeed(scope: String, tradeDate: LocalDate): SentimentRuntimeSeed?
    fun loadFirstAdj(tsCodes: List<String>): Map<String, Float>
    fun loadAdjByTradeDate(tradeDate: LocalDate, tsCodes: List<String>): Map<String, Float>

    /**
     * 从 Ktor DAY snapshot 一次性把当前 universe 的实时日 K 写入 fact cache。
     * 在一次 [IntradayStrategyRuntime.refresh] 开头调用一次，
     * 之后的 [loadRealtimeDailyCandles] 子集查询都从同一份 cache 命中，
     * strategy-service 自身不直接访问 Tushare 或自建实时轮询。
     */
    suspend fun prefetchRealtimeDailyCandles(tsCodes: List<String>, tradeDate: LocalDate)

    suspend fun loadRealtimeDailyCandles(tsCodes: List<String>, tradeDate: LocalDate): Map<String, Candle>
    fun findPreviousTradingDate(tradeDate: LocalDate): LocalDate?
    fun loadStockFactorsByDate(tradeDate: LocalDate, tsCodes: List<String>): List<DomainFactorSnapshot>
    fun loadLatestStockFactorsBefore(
        tradeDate: LocalDate,
        tsCodes: List<String>
    ): Pair<LocalDate, List<DomainFactorSnapshot>>?
    fun loadFactorStates(tradeDate: LocalDate, tsCodes: List<String>): List<FactorRollingState>
    fun loadSentimentByDate(tradeDate: LocalDate): DomainSentimentSnapshot?
    fun loadCurrentPositionCodes(tradeDate: LocalDate): List<String>
    fun loadPostMarketTargetPositions(targetDate: LocalDate): List<DomainTargetPosition>
}

class DefaultIntradayStrategyRuntimeDataSource(
    private val realtimeFactSource: StrategyRealtimeDailyFactSource
) : IntradayStrategyRuntimeDataSource {

    override fun resolveTradeDate(): LocalDate {
        val today = kotlin.time.Clock.System.now()
            .toLocalDateTime(TimeZone.of("Asia/Shanghai"))
            .date
        return TradingCalendarRepository.findLatestTradingDateOnOrBefore(today) ?: today
    }

    override fun loadActiveUniverse(): List<String> = MainBoardUniverseProvider.getActiveSymbols()

    override fun loadRecentSentiments(
        endDateInclusive: LocalDate,
        limit: Int
    ): List<DomainSentimentSnapshot> = DailyMarketSentimentRepository.findRecentUpToDate(
        endDateInclusive = endDateInclusive,
        limit = limit
    )

    override fun loadSentimentSeed(scope: String, tradeDate: LocalDate): SentimentRuntimeSeed? =
        SentimentRuntimeSeedRepository.find(scope, tradeDate)

    override fun loadFirstAdj(tsCodes: List<String>): Map<String, Float> =
        StockReader.getFirstAdjMap(tsCodes)

    override fun loadAdjByTradeDate(tradeDate: LocalDate, tsCodes: List<String>): Map<String, Float> =
        StockDailyCandleRepository.findAdjByTradeDate(tradeDate, tsCodes)

    override suspend fun prefetchRealtimeDailyCandles(tsCodes: List<String>, tradeDate: LocalDate) {
        realtimeFactSource.prefetch(tsCodes, tradeDate)
    }

    override suspend fun loadRealtimeDailyCandles(tsCodes: List<String>, tradeDate: LocalDate): Map<String, Candle> =
        realtimeFactSource.load(tsCodes, tradeDate)
            .filter { it.date == tradeDate }
            .associateBy { it.tsCode }

    override fun findPreviousTradingDate(tradeDate: LocalDate): LocalDate? =
        TradingCalendarRepository.findPreviousTradingDate(tradeDate)

    override fun loadStockFactorsByDate(
        tradeDate: LocalDate,
        tsCodes: List<String>
    ): List<DomainFactorSnapshot> = DailyStockFactorRepository.findByDate(tradeDate, tsCodes)

    override fun loadLatestStockFactorsBefore(
        tradeDate: LocalDate,
        tsCodes: List<String>
    ): Pair<LocalDate, List<DomainFactorSnapshot>>? =
        DailyStockFactorRepository.findLatestBefore(tradeDate, tsCodes)

    override fun loadFactorStates(tradeDate: LocalDate, tsCodes: List<String>): List<FactorRollingState> =
        DailyFactorRollingStateRepository.findByDateAndTsCodes(tradeDate, tsCodes)

    override fun loadSentimentByDate(tradeDate: LocalDate): DomainSentimentSnapshot? =
        DailyMarketSentimentRepository.findByDate(tradeDate)

    override fun loadCurrentPositionCodes(tradeDate: LocalDate): List<String> =
        DailyStrategyHoldingRepository.findByTradeDate(tradeDate)
            .map { it.tsCode }
            .ifEmpty {
                DailyStrategyAuditRepository.getRecentRecords(1)
                    .firstOrNull()
                    ?.currentPositions
                    .orEmpty()
            }

    override fun loadPostMarketTargetPositions(targetDate: LocalDate): List<DomainTargetPosition> =
        DailyProfitPredictionSelectionRepository.findTargetsByTargetDate(targetDate)
            .filter { it.selected }
            .map {
                DomainTargetPosition(
                    tradeDate = it.tradeDate,
                    targetDate = it.targetDate,
                    tsCode = it.tsCode,
                    selectionScore = it.modelScore,
                    selected = it.selected,
                    targetWeight = it.targetWeight,
                    sentimentExposure = it.sentimentExposure,
                    selectionReason = it.selectionReason,
                )
            }
}
