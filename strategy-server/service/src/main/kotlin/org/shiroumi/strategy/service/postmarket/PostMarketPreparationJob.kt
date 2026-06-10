package org.shiroumi.strategy.service.postmarket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import model.Candle
import model.PriceBasis
import model.dataprovider.SentimentScopes
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.database.strategy.daily.repository.DailyHoldingState
import org.shiroumi.database.strategy.daily.repository.DailyStrategyHoldingRepository
import org.shiroumi.database.strategy.daily.repository.DailyFactorRollingStateRepository
import org.shiroumi.database.strategy.daily.repository.DailyMarketSentimentRepository
import org.shiroumi.database.strategy.daily.repository.DailyMarketSentimentStateRepository
import org.shiroumi.database.strategy.daily.repository.DailyStockFactorRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.database.strategy.daily.repository.DefaultStrategyBarRepository
import org.shiroumi.database.strategy.daily.repository.SentimentRuntimeSeedRepository
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedBar
import org.shiroumi.strategy.core.audit.StrategyAuditGenerator
import org.shiroumi.strategy.core.daily.FactorRollingState
import org.shiroumi.strategy.core.daily.MarketSentimentCalculator
import org.shiroumi.strategy.core.daily.MarketSentimentRollingState
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.StockFactorCalculator
import org.shiroumi.strategy.core.daily.StockFactorSnapshot
import org.shiroumi.strategy.core.daily.preprocessing.PreparedBarFactory
import org.shiroumi.strategy.core.daily.seed.toRuntimeSeed
import org.shiroumi.strategy.service.model.ProfitPredictionModelSelector
import org.shiroumi.strategy.service.preprocessing.DefaultStrategyPreprocessor
import org.shiroumi.strategy.service.universe.MainBoardUniverseProvider
import utils.logger
import kotlin.math.min

private val logger by logger("PostMarketPreparationJob")

/**
 * 盘后预处理任务结果。
 *
 * [holdings] 为当日收盘后的持仓状态快照（含止盈止损推演后的留存 + 当日新入场），
 * 供 orchestrator 逐日链式推进。
 */
data class PostMarketPreparationResult(
    val holdings: List<DailyHoldingState>,
)

private data class ChunkPreparationResult(
    val barsBySymbol: Map<String, List<PreparedBar>>,
    val factorSnapshots: List<StockFactorSnapshot>,
    val insufficientSymbols: List<String>,
    val finalStates: List<FactorRollingState>,
)

private data class IncrementalPreparedBars(
    val barsBySymbol: Map<String, PreparedBar>,
    val missingCandleCodes: Set<String>,
    val missingFirstAdjCodes: Set<String>,
)

/**
 * 盘后单日策略预处理 21 步算法链。
 *
 * 业务主权所在：strategy-server。
 * - [MainBoardUniverseProvider] 位于 `strategy-server:service`
 * - [StrategyAuditGenerator] 位于 `strategy-server:core`
 * - [DefaultStrategyPreprocessor] 位于 `strategy-server:service`
 * database 仅提供 Repository / schema 等持久化能力。
 */
object PostMarketPreparationJob {
    private val profitPredictionSelector = ProfitPredictionModelSelector()
    private val holdingStateMachine = HoldingStateMachine()

    suspend fun run(
        tradeDate: LocalDate,
        startDate: LocalDate,
        endDate: LocalDate,
        previousHoldings: List<DailyHoldingState>,
        requiredHistory: Int = 400,
        signalBasis: PriceBasis = PriceBasis.HFQ,
        chunkSize: Int = 300,
        parallelism: Int? = null,
    ): PostMarketPreparationResult {
        logger.info(
            "[策略预处理] 开始执行 | tradeDate=$tradeDate, startDate=$startDate, endDate=$endDate, " +
                "requiredHistory=$requiredHistory, signalBasis=$signalBasis, chunkSize=$chunkSize"
        )

        val universeSymbols = MainBoardUniverseProvider.getActiveSymbols()
        val previousTradeDate = TradingCalendarRepository.findPreviousTradingDate(tradeDate)
        val previousStates = if (previousTradeDate != null) {
            DailyFactorRollingStateRepository.findByDateAndTsCodes(previousTradeDate, universeSymbols)
        } else {
            emptyList()
        }
        val stateBySymbol = previousStates.associateBy { it.tsCode }
        val preprocessor = DefaultStrategyPreprocessor()
        val marketBarsBySymbol = linkedMapOf<String, List<PreparedBar>>()
        var allSufficient = universeSymbols.isNotEmpty()
        val insufficientSymbols = mutableListOf<String>()
        val allFactors = mutableListOf<StockFactorSnapshot>()
        val allFinalStates = mutableListOf<FactorRollingState>()

        val effectiveParallelism = parallelism
            ?: min(Runtime.getRuntime().availableProcessors(), 8).coerceAtLeast(1)
        val chunkDispatcher = Dispatchers.Default.limitedParallelism(effectiveParallelism)
        val chunkResults = coroutineScope {
            universeSymbols.chunked(chunkSize).map { chunk ->
                async(chunkDispatcher) {
                    val incrementalSymbols = chunk.filter { stateBySymbol.containsKey(it) }
                    val fullSymbols = chunk.filterNot { stateBySymbol.containsKey(it) }

                    val barsBySymbol = linkedMapOf<String, List<PreparedBar>>()
                    val factorSnapshots = ArrayList<StockFactorSnapshot>()
                    val insufficientInChunk = ArrayList<String>()
                    val chunkStates = ArrayList<FactorRollingState>()

                    if (fullSymbols.isNotEmpty()) {
                        val stockWindows = preprocessor.prepareStockWindows(
                            tsCodes = fullSymbols,
                            startDate = startDate,
                            endDate = endDate,
                            requiredHistory = requiredHistory,
                            signalBasis = signalBasis,
                            executionBasis = PriceBasis.RAW,
                        )
                        stockWindows.values.forEach { window ->
                            if (!window.sufficientHistory) {
                                insufficientInChunk += window.tsCode
                                return@forEach
                            }
                            if (window.bars.isNotEmpty()) {
                                barsBySymbol[window.tsCode] = window.bars
                            }
                            val result = StockFactorCalculator.process(window)
                            if (result != null) {
                                chunkStates += result.first
                                factorSnapshots += result.second
                            } else {
                                insufficientInChunk += window.tsCode
                            }
                        }
                    }

                    if (incrementalSymbols.isNotEmpty()) {
                        val histories = DefaultStrategyBarRepository.getBatchStockHistory(
                            incrementalSymbols, tradeDate, tradeDate
                        )
                        val firstAdjMap = DefaultStrategyBarRepository.getFirstAdjMap(incrementalSymbols)
                        val incrementalPrepared = prepareIncrementalBars(
                            tsCodes = incrementalSymbols,
                            histories = histories,
                            firstAdjMap = firstAdjMap,
                            signalBasis = signalBasis,
                        )
                        logFirstAdjCoverage(
                            stage = "FACTOR_INCREMENTAL",
                            total = incrementalSymbols.size,
                            hitCount = incrementalSymbols.size - incrementalPrepared.missingFirstAdjCodes.size,
                            missingFirstAdjCodes = incrementalPrepared.missingFirstAdjCodes.toList(),
                            path = "增量",
                        )

                        incrementalSymbols.forEach { tsCode ->
                            val bar = incrementalPrepared.barsBySymbol[tsCode]
                            val state = stateBySymbol.getValue(tsCode)
                            if (bar == null) {
                                return@forEach
                            }
                            StockFactorCalculator.calculate(state, bar)?.let { (nextState, snap) ->
                                chunkStates += nextState
                                factorSnapshots += snap
                            }
                        }
                    }

                    ChunkPreparationResult(
                        barsBySymbol = barsBySymbol,
                        factorSnapshots = factorSnapshots,
                        insufficientSymbols = insufficientInChunk,
                        finalStates = chunkStates,
                    )
                }
            }.awaitAll()
        }

        chunkResults.forEach { chunkResult ->
            marketBarsBySymbol.putAll(chunkResult.barsBySymbol)
            allFactors += chunkResult.factorSnapshots
            allFinalStates += chunkResult.finalStates
            if (chunkResult.insufficientSymbols.isNotEmpty()) {
                allSufficient = false
                insufficientSymbols += chunkResult.insufficientSymbols
            }
        }

        logger.info("[策略预处理] 分块处理完成 | 有效因子数=${allFactors.size}, 历史不足数=${insufficientSymbols.size}")

        DailyFactorRollingStateRepository.replaceBatch(
            tradeDate = tradeDate,
            states = allFinalStates,
        )
        DailyStockFactorRepository.replaceForDate(
            tradeDate = tradeDate,
            snapshots = allFactors,
        )

        val sentimentSampleCodes = universeSymbols
        val previousSentimentState = previousTradeDate?.let {
            DailyMarketSentimentStateRepository.findByDate(it)
        }

        val (sentimentState, sentimentSnapshot) = if (
            previousSentimentState != null &&
            previousSentimentState.tradeDate == previousTradeDate
        ) {
            val histories = DefaultStrategyBarRepository.getBatchStockHistory(
                sentimentSampleCodes, tradeDate, tradeDate
            )
            val firstAdjMap = DefaultStrategyBarRepository.getFirstAdjMap(sentimentSampleCodes)
            val todayPrepared = prepareIncrementalBars(
                tsCodes = sentimentSampleCodes,
                histories = histories,
                firstAdjMap = firstAdjMap,
                signalBasis = signalBasis,
            )
            val todayBarsBySymbol = todayPrepared.barsBySymbol
            logFirstAdjCoverage(
                stage = "MARKET_SENTIMENT_INCREMENTAL",
                total = sentimentSampleCodes.size,
                hitCount = sentimentSampleCodes.size - todayPrepared.missingFirstAdjCodes.size,
                missingFirstAdjCodes = todayPrepared.missingFirstAdjCodes.toList(),
                path = "增量",
            )

            val prevCodes = previousSentimentState.sampleCodes.toSet()
            val diffCodes = sentimentSampleCodes.filterNot { prevCodes.contains(it) }

            val diffStates = if (diffCodes.isNotEmpty()) {
                val diffWindows = preprocessor.prepareStockWindows(
                    tsCodes = diffCodes,
                    startDate = startDate,
                    endDate = previousTradeDate,
                    requiredHistory = requiredHistory,
                    signalBasis = signalBasis,
                    executionBasis = PriceBasis.RAW,
                )
                val diffBarsBySymbol = diffWindows.values
                    .filter { it.sufficientHistory && it.bars.isNotEmpty() }
                    .associate { it.tsCode to it.bars }
                val alignedDiffBars = MarketSentimentCalculator.alignBarsBySymbol(diffBarsBySymbol)
                alignedDiffBars.mapNotNull { (tsCode, bars) ->
                    MarketSentimentCalculator.rebuildSymbolState(tsCode, bars)
                }
            } else emptyList()

            val validDiffCodes = diffStates.map { it.tsCode }.toSet()
            val validSampleCodes = sentimentSampleCodes.filter {
                (prevCodes.contains(it) || validDiffCodes.contains(it)) &&
                    !todayPrepared.missingFirstAdjCodes.contains(it)
            }.sorted()

            val mergedSymbolStates = buildList {
                addAll(previousSentimentState.symbolStates.filter { validSampleCodes.contains(it.tsCode) })
                addAll(diffStates.filter { validSampleCodes.contains(it.tsCode) })
            }
            val mergedState = previousSentimentState.copy(
                sampleCodes = validSampleCodes,
                symbolStates = mergedSymbolStates,
            )
            val validTodayBars = todayBarsBySymbol.filterKeys { validSampleCodes.contains(it) }
            logger.info(
                "[市场情绪][SAMPLE_SUMMARY] path=增量, total=${sentimentSampleCodes.size}, " +
                    "prevCarry=${prevCodes.size}, diffCandidates=${diffCodes.size}, diffValid=${validDiffCodes.size}, " +
                    "missingFirstAdj=${todayPrepared.missingFirstAdjCodes.size}, missingTodayBar=${todayPrepared.missingCandleCodes.size}, " +
                    "effective=${validSampleCodes.size}"
            )

            if (validSampleCodes.isNotEmpty()) {
                MarketSentimentCalculator.calculate(
                    state = mergedState,
                    todayBarsBySymbol = validTodayBars,
                    tradeDate = tradeDate,
                    requiredHistory = requiredHistory,
                )
            } else {
                val fallbackSnap = MarketSentimentSnapshot(
                    tradeDate = tradeDate,
                    signalBasis = signalBasis.name,
                    sampleSize = 0,
                    bullRatio = 0.0,
                    fftScore = 0.0,
                    residualScore = 0.0,
                    marketVol = 0.0,
                    volZ = 0.0,
                    accelZ = 0.0,
                    sentimentExposure = 0.0,
                    ratioNorm = 0.0,
                    volScore = 0.0,
                    accelScore = 0.0,
                    absoluteFloor = 0.0,
                    volCap = 0.0,
                    sufficientHistory = false,
                    requiredHistory = requiredHistory,
                    reason = "无有效市场情绪样本",
                )
                mergedState.copy(tradeDate = tradeDate) to fallbackSnap
            }
        } else {
            val sentimentWindows = preprocessor.prepareStockWindows(
                tsCodes = sentimentSampleCodes,
                startDate = startDate,
                endDate = endDate,
                requiredHistory = requiredHistory,
                signalBasis = signalBasis,
                executionBasis = PriceBasis.RAW,
            )
            val sentimentBarsBySymbol = sentimentWindows.values
                .filter { it.sufficientHistory && it.bars.isNotEmpty() }
                .associate { it.tsCode to it.bars }
            val missingFirstAdjCodes = sentimentWindows.values
                .filter { it.reason == "缺失 firstAdj" }
                .map { it.tsCode }
            logFirstAdjCoverage(
                stage = "MARKET_SENTIMENT_FULL",
                total = sentimentSampleCodes.size,
                hitCount = sentimentSampleCodes.size - missingFirstAdjCodes.size,
                missingFirstAdjCodes = missingFirstAdjCodes,
                path = "全量",
            )
            logger.info(
                "[市场情绪][SAMPLE_SUMMARY] path=全量, total=${sentimentSampleCodes.size}, " +
                    "missingFirstAdj=${missingFirstAdjCodes.size}, effective=${sentimentBarsBySymbol.size}"
            )

            if (sentimentBarsBySymbol.isNotEmpty()) {
                MarketSentimentCalculator.process(
                    barsBySymbol = sentimentBarsBySymbol,
                    requiredHistory = requiredHistory,
                )
            } else {
                val fallbackSnap = MarketSentimentSnapshot(
                    tradeDate = tradeDate,
                    signalBasis = signalBasis.name,
                    sampleSize = 0,
                    bullRatio = 0.0,
                    fftScore = 0.0,
                    residualScore = 0.0,
                    marketVol = 0.0,
                    volZ = 0.0,
                    accelZ = 0.0,
                    sentimentExposure = 0.0,
                    ratioNorm = 0.0,
                    volScore = 0.0,
                    accelScore = 0.0,
                    absoluteFloor = 0.0,
                    volCap = 0.0,
                    sufficientHistory = false,
                    requiredHistory = requiredHistory,
                    reason = when {
                        universeSymbols.isEmpty() -> "股票池为空"
                        sentimentBarsBySymbol.isEmpty() -> "无有效市场情绪样本行情数据"
                        else -> null
                    },
                )
                val fallbackState = MarketSentimentRollingState(
                    tradeDate = tradeDate,
                    signalBasis = signalBasis.name,
                    sampleCodes = emptyList(),
                    symbolStates = emptyList(),
                    bullRatioHistory = emptyList(),
                    marketVolHistory = emptyList(),
                    accelHistory = emptyList(),
                    combinedHistory = emptyList(),
                    totalDays = 0,
                )
                fallbackState to fallbackSnap
            }
        }

        if (sentimentSnapshot.sufficientHistory) {
            DailyMarketSentimentStateRepository.replace(sentimentState)
        }

        DailyMarketSentimentRepository.replaceForDate(sentimentSnapshot)
        persistNextTradeDateSentimentSeed(
            tradeDate = tradeDate,
            sentimentSnapshot = sentimentSnapshot,
            sentimentState = sentimentState,
        )
        logger.info(
            "[策略预处理] 市场情绪计算完成 | exposure=${sentimentSnapshot.sentimentExposure}, " +
                "bullRatio=${sentimentSnapshot.bullRatio}, sufficientHistory=${sentimentSnapshot.sufficientHistory}"
        )

        val nextTradeDate = TradingCalendarRepository.findNextTradingDate(tradeDate) ?: tradeDate
        val targets = profitPredictionSelector.generateTargets(
            tradeDate = tradeDate,
            targetDate = nextTradeDate,
            universeSymbols = universeSymbols,
            sentiment = sentimentSnapshot,
        )
        logger.info(
            "[策略预处理] 目标组合生成完成 | selectedCount=${targets.count { it.selected }}, " +
                "totalPositions=${targets.size}, exposure=${sentimentSnapshot.sentimentExposure}"
        )

        DailyProfitPredictionSelectionRepository.deleteByDate(tradeDate)
        DailyProfitPredictionSelectionRepository.replaceForDate(
            tradeDate = tradeDate,
            positions = targets,
        )

        // 持仓状态机推进：用前一交易日选股（target_date=tradeDate 的 selected 票）作为当日新入场候选，
        // 对前一交易日持仓做止盈止损判定，产出当日持仓快照。
        val holdingResult = advanceHoldings(
            tradeDate = tradeDate,
            previousTradeDate = previousTradeDate,
            previousHoldings = previousHoldings,
        )
        DailyStrategyHoldingRepository.replaceForDate(tradeDate, holdingResult.holdings)

        val currentPositionSymbols = holdingResult.holdings.map { it.tsCode }.toSet()
        val previousPositionSymbols = previousHoldings.map { it.tsCode }.toSet()
        val auditSummary = StrategyAuditGenerator.generate(
            tradeDate = tradeDate,
            universeSize = universeSymbols.size,
            factors = allFactors,
            sentiment = sentimentSnapshot,
            targets = targets,
            previousCurrentPositions = previousPositionSymbols,
            currentPositions = currentPositionSymbols,
        )

        DailyStrategyAuditRepository.replaceForDate(auditSummary)
        logger.info(
            "[策略预处理] 执行完成 | tradeDate=$tradeDate, selected=${auditSummary.selectedCount}, " +
                "持仓=${currentPositionSymbols.size}, 新入场=${holdingResult.entered.size}, " +
                "离场=${holdingResult.exited.size}, emptyReason=${auditSummary.emptyReason ?: "无"}"
        )

        return PostMarketPreparationResult(holdings = holdingResult.holdings)
    }

    /**
     * 推进当日持仓状态机。
     *
     * - 新入场候选：前一交易日选出、target_date=tradeDate 的 selected 票（今天开盘买入）。
     *   信号日（选股日，T 日）low/close 取 [previousTradeDate] 蜡烛：low 用于价格止损（默认关闭），
     *   close 用于入场跳空过滤（开盘较信号日收盘跳空 > 3% 不入场）。
     * - 行情：当日蜡烛用于退出判定与入场价；前一日蜡烛用于新入场票信号日基准。
     * - 交易日间隔由 [TradingCalendarRepository] 计算。
     */
    private fun advanceHoldings(
        tradeDate: LocalDate,
        previousTradeDate: LocalDate?,
        previousHoldings: List<DailyHoldingState>,
    ): HoldingStateMachine.DayResult {
        val todayCandles = StockDailyCandleRepository.findByTradeDate(tradeDate).associateBy { it.tsCode }
        val signalDayCandleByCode: Map<String, model.Candle> = if (previousTradeDate != null) {
            StockDailyCandleRepository.findByTradeDate(previousTradeDate).associateBy { it.tsCode }
        } else {
            emptyMap()
        }

        // 新入场候选：前一交易日选出、今日生效买入的 selected 票
        val newEntries = DailyProfitPredictionSelectionRepository
            .findSelectionsByTargetDate(tradeDate)
            .map { selection ->
                val signalBar = signalDayCandleByCode[selection.tsCode]
                HoldingStateMachine.EntryCandidate(
                    tsCode = selection.tsCode,
                    signalDateLow = signalBar?.getLow()?.toDouble() ?: 0.0,
                    signalDateClose = signalBar?.getPrice()?.toDouble() ?: 0.0,
                )
            }

        return holdingStateMachine.advance(
            tradeDate = tradeDate,
            previousHoldings = previousHoldings,
            newEntries = newEntries,
            tradingDaysSince = { entryDate, date ->
                if (date <= entryDate) 0
                else (TradingCalendarRepository.findOpenDates(entryDate, date).size - 1).coerceAtLeast(0)
            },
            candleFor = { tsCode, date ->
                if (date == tradeDate) todayCandles[tsCode] else null
            },
        )
    }

    private fun persistNextTradeDateSentimentSeed(
        tradeDate: LocalDate,
        sentimentSnapshot: MarketSentimentSnapshot,
        sentimentState: MarketSentimentRollingState,
    ) {
        if (!sentimentSnapshot.sufficientHistory) {
            logger.warning(
                "[策略预处理] 情绪历史不足，跳过次日 seed 生成 | tradeDate=$tradeDate, reason=${sentimentSnapshot.reason}"
            )
            return
        }
        if (sentimentState.symbolStates.isEmpty()) {
            logger.error("[策略预处理] 情绪状态 symbolStates 为空，跳过次日 seed 生成 | tradeDate=$tradeDate")
            return
        }
        val nextTradeDate = TradingCalendarRepository.findNextTradingDate(tradeDate) ?: run {
            logger.info("[策略预处理] 未找到 $tradeDate 之后的交易日，跳过情绪 seed 生成")
            return
        }
        val seed = sentimentState.toRuntimeSeed(
            scope = SentimentScopes.MAIN_BOARD,
            forTradeDate = nextTradeDate,
            requiredHistory = sentimentSnapshot.requiredHistory
        )
        SentimentRuntimeSeedRepository.replace(seed)
    }

    private fun prepareIncrementalBars(
        tsCodes: List<String>,
        histories: Map<String, List<Candle>>,
        firstAdjMap: Map<String, Float>,
        signalBasis: PriceBasis,
    ): IncrementalPreparedBars {
        val barsBySymbol = linkedMapOf<String, PreparedBar>()
        val missingCandleCodes = linkedSetOf<String>()
        val missingFirstAdjCodes = linkedSetOf<String>()
        tsCodes.forEach { tsCode ->
            val candle = histories[tsCode].orEmpty().firstOrNull()
            if (candle == null) {
                missingCandleCodes += tsCode
                return@forEach
            }
            val normalizedFirstAdj = when (signalBasis) {
                PriceBasis.HFQ -> firstAdjMap[tsCode]?.takeIf { it > 0f } ?: run {
                    missingFirstAdjCodes += tsCode
                    return@forEach
                }
                else -> firstAdjMap[tsCode]?.takeIf { it > 0f } ?: 1f
            }
            barsBySymbol[tsCode] = PreparedBarFactory.fromCandle(
                candle = candle,
                normalizedFirstAdj = normalizedFirstAdj,
                signalBasis = signalBasis,
                executionBasis = PriceBasis.RAW,
            )
        }
        return IncrementalPreparedBars(
            barsBySymbol = barsBySymbol,
            missingCandleCodes = missingCandleCodes,
            missingFirstAdjCodes = missingFirstAdjCodes,
        )
    }

    private fun logFirstAdjCoverage(
        stage: String,
        total: Int,
        hitCount: Int,
        missingFirstAdjCodes: List<String>,
        path: String,
    ) {
        if (!stage.contains("MARKET_SENTIMENT")) return

        if (missingFirstAdjCodes.isNotEmpty()) {
            logger.warning(
                "[市场情绪][HFQ_SAMPLE_EXCLUDED] stage=$stage, path=$path, total=$total, " +
                    "firstAdjHits=$hitCount, missingFirstAdj=${missingFirstAdjCodes.size}, " +
                    "sample=${missingFirstAdjCodes.take(20).joinToString(",")}"
            )
        } else {
            logger.info(
                "[市场情绪][HFQ_REFERENCE_OK] stage=$stage, path=$path, total=$total, firstAdjHits=$hitCount, missingFirstAdj=0"
            )
        }

        if (total > 0 && hitCount == 0) {
            logger.error("[市场情绪][HFQ_REFERENCE_EMPTY] stage=$stage, path=$path, total=$total, firstAdjHits=0")
        }
    }
}
