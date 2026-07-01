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
import org.shiroumi.strategy.core.daily.TargetPosition
import org.shiroumi.strategy.core.daily.preprocessing.PreparedBarFactory
import org.shiroumi.strategy.core.daily.seed.toRuntimeSeed
import org.shiroumi.strategy.service.model.Ema20SlopeTargetSelector
import org.shiroumi.strategy.service.model.ProfitPredictionModelSelector
import org.shiroumi.strategy.service.model.ProfitPredictionTargetSelector
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
    /**
     * 选股引擎装配开关：`quant.selection.engine`。
     * - 默认 / `profit-prediction`：v5 盈利预测模型 Top-N（现状口径，不变）。
     * - `ema20-slope`：纯 EMA20 斜率 Top-N 趋势池（2026-06-29 换口径，用户拍板）。
     *
     * 两实现同 [ProfitPredictionTargetSelector] 契约，下游写库 / 买点回填 / 持仓推进零差别。
     * 换口径首日落库需配 `-Dquant.strategy.rebuild.allowSelectionDrift=true` 放行 selection 漂移。
     */
    private val profitPredictionSelector: ProfitPredictionTargetSelector =
        when (System.getProperty("quant.selection.engine", "profit-prediction").lowercase()) {
            "ema20-slope" -> Ema20SlopeTargetSelector().also {
                logger.info("[策略预处理] 选股引擎 = EMA20 斜率趋势池（quant.selection.engine=ema20-slope）")
            }
            else -> ProfitPredictionModelSelector()
        }

    // 规则装配统一走 ExitRules.fromSystemProperties()，与持仓跟踪展示链路共享同一规则口径
    private val holdingStateMachine = HoldingStateMachine(
        HoldingStateMachine.ExitRules.fromSystemProperties().also {
            if (it != HoldingStateMachine.ExitRules()) {
                logger.info("[策略预处理] 持仓规则非默认配置生效 | $it")
            }
        }
    )

    // 影子对照：旧运营点 tp7/u25/H5 同步推演（只比对记录，不落库、不影响生产持仓）。
    // 目的：验证 tp8/u25/H3 切换（2026-06-13）的回测增益在实盘逐日复现；改善衰减过半即回滚。
    private val shadowStateMachine = HoldingStateMachine(HoldingStateMachine.ExitRules.V5_FAST_TP7_H5)

    suspend fun run(
        tradeDate: LocalDate,
        startDate: LocalDate,
        endDate: LocalDate,
        previousHoldings: List<DailyHoldingState>,
        requiredHistory: Int = 400,
        signalBasis: PriceBasis = PriceBasis.HFQ,
        chunkSize: Int = 300,
        parallelism: Int? = null,
        // 持仓推进的逐日蜡烛供给器（重建编排层注入滑窗预取，避免「同日被扫两次」2 倍冗余）。
        // 默认 null：在线盘后链路回退 StockDailyCandleRepository.findByTradeDate，零行为变化。
        candleProvider: ((LocalDate) -> Map<String, Candle>)? = null,
        // 重建区间有序交易日索引（升序）：持仓推进 tradingDaysSince 段内二分定位用，不改数值。
        // 默认 null：回退 TradingCalendarRepository.findOpenDates 查库。
        tradeDateIndex: List<LocalDate>? = null,
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

        // selection 复现断言：全链重算会覆盖 daily_profit_prediction_selection；覆盖前先与历史落库逐票比对，
        // 不一致时由 SelectionDriftGuard 决策（默认拒绝写库并抛错，除非显式放行漂移）。
        SelectionDriftGuard.assertReproducible(tradeDate, targets)
        // 重算买点保留：delete 前快照本批已落库的非空买点，覆盖写时回填，使下方 agent 买点回填的
        // 「跳过已有买点」幂等优化在跨重算（数据更新追平/补偿重跑）场景仍生效——否则 replaceForDate
        // 整行覆盖把 limit_price 抹回 null，每次重算都对全部 selected 票重跑 agent（浪费算力且买点价漂移）。
        // 复用安全性由上方 SelectionDriftGuard 保证：selected 集合与历史一致才放行覆盖，旧买点对应同一只票。
        val priorLimitPrices = DailyProfitPredictionSelectionRepository.findLimitPricesByTradeDate(tradeDate)
        val targetsWithLimit = mergePriorLimitPrices(targets, priorLimitPrices)
        DailyProfitPredictionSelectionRepository.deleteByDate(tradeDate)
        DailyProfitPredictionSelectionRepository.replaceForDate(
            tradeDate = tradeDate,
            positions = targetsWithLimit,
        )

        // agent 买点回填：对刚选出的 target_date=nextTradeDate 这批 selected Top-N 票并发跑 agent 量价分析，
        // 回填 daily_profit_prediction_selection.limit_price。买点同时生效于跟踪页「目标买点」展示与
        // 次日盘后 advanceHoldings 的 LIMIT 入场口径（当日 advanceHoldings 读的是 target_date=tradeDate
        // 那批，不是本批，故回填本批不影响当日入场）。覆盖率不足时阻断盘后走补偿队列重跑。
        val backfillConfig = AgentEntryBackfillConfig.fromSystemProperties()
        if (backfillConfig.enabled) {
            val outcome = AgentEntryBackfillStep.backfill(targetDate = nextTradeDate, config = backfillConfig)
            if (outcome.coverage < backfillConfig.minCoverage) {
                throw AgentEntryBackfillInsufficientCoverageException(
                    "买点回填覆盖率不足 target_date=$nextTradeDate filled=${outcome.filled}/${outcome.candidates} " +
                        "coverage=${outcome.coverage} min=${backfillConfig.minCoverage}"
                )
            }
        }

        // 持仓状态机推进：用前一交易日选股（target_date=tradeDate 的 selected 票）作为当日新入场候选，
        // 对前一交易日持仓做止盈止损判定，产出当日持仓快照。
        val holdingResult = advanceHoldings(
            tradeDate = tradeDate,
            previousTradeDate = previousTradeDate,
            previousHoldings = previousHoldings,
            candleProvider = candleProvider,
            tradeDateIndex = tradeDateIndex,
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
        candleProvider: ((LocalDate) -> Map<String, Candle>)? = null,
        tradeDateIndex: List<LocalDate>? = null,
    ): HoldingStateMachine.DayResult {
        // 逐日蜡烛取数：供给器存在时走滑窗预取（已按 ts_code 索引），否则回退按交易日查库。
        // 两条路共用 toCandle() 不裁列，advance 数值严格一致。
        val candlesFor: (LocalDate) -> Map<String, Candle> = { date ->
            candleProvider?.invoke(date)
                ?: StockDailyCandleRepository.findByTradeDate(date).associateBy { it.tsCode }
        }
        val todayCandles = candlesFor(tradeDate)
        val signalDayCandleByCode: Map<String, model.Candle> = if (previousTradeDate != null) {
            candlesFor(previousTradeDate)
        } else {
            emptyMap()
        }

        // 新入场候选：前一交易日选出、今日生效买入的 selected 票。
        // 每日入场上限开启时，入场优先级 = 模型分（selection.modelScore），advance 按 entryPriority 降序
        // 取前 maxDailyEntries 只，即每日入场模型分最高的若干只（findSelectionsByTargetDate 已按模型分降序）。
        val entryCapEnabled = holdingStateMachine.entryCapEnabled
        val selections = DailyProfitPredictionSelectionRepository.findSelectionsByTargetDate(tradeDate)
        // 破位加分：开关开启时锚信号日 T=previousTradeDate 现算破位事件，破位票入场排序前置。
        // 与持仓跟踪重放（StrategyPositionTrackingRuntime）同源注入同一判定，避免生产持仓与重放分叉。
        val breakdownFlags: Map<String, Boolean> =
            if (entryCapEnabled && previousTradeDate != null) {
                BreakdownRerankService.evaluate(selections.map { it.tsCode }, previousTradeDate)
            } else {
                emptyMap()
            }
        val newEntries = selections.map { selection ->
            val signalBar = signalDayCandleByCode[selection.tsCode]
            HoldingStateMachine.EntryCandidate(
                tsCode = selection.tsCode,
                signalDateLow = signalBar?.getLow()?.toDouble() ?: 0.0,
                signalDateClose = signalBar?.getPrice()?.toDouble() ?: 0.0,
                entryPriority = if (entryCapEnabled) selection.modelScore else 0.0,
                breakdownFlag = breakdownFlags[selection.tsCode] ?: false,
                // Agent 量价买点：有值走 LIMIT 触达入场，缺值（未分析/失败）回退开盘价无条件建仓。
                entryLimitPrice = selection.limitPrice,
            )
        }

        // 交易日间隔（不含 entry，含 date）。重建注入有序交易日索引时段内二分定位，省去逐次查库；
        // 二分仅在 entryDate 与 date 均落在索引内时生效（索引是区间内连续交易日，差值即间隔），
        // 否则（如携带入场日早于区间的初值持仓）回退查库，数值严格一致。
        val tradingDaysSince: (LocalDate, LocalDate) -> Int = { entryDate, date ->
            if (date <= entryDate) {
                0
            } else {
                val viaIndex = tradeDateIndex?.let { index ->
                    val entryPos = binarySearchExact(index, entryDate)
                    val datePos = binarySearchExact(index, date)
                    if (entryPos >= 0 && datePos >= 0) (datePos - entryPos).coerceAtLeast(0) else null
                }
                viaIndex
                    ?: (TradingCalendarRepository.findOpenDates(entryDate, date).size - 1).coerceAtLeast(0)
            }
        }
        val candleFor: (String, LocalDate) -> model.Candle? = { tsCode, date ->
            if (date == tradeDate) todayCandles[tsCode] else null
        }
        val result = holdingStateMachine.advance(
            tradeDate = tradeDate,
            previousHoldings = previousHoldings,
            newEntries = newEntries,
            tradingDaysSince = tradingDaysSince,
            candleFor = candleFor,
        )
        logShadowDivergence(tradeDate, previousHoldings, newEntries, tradingDaysSince, candleFor, result)
        return result
    }

    /**
     * 影子对照（tp8/u25/H3 现行 vs tp7/u25/H5 旧运营点）：对同一份生产持仓做单日双判决并记录分歧。
     * 仅日志不落库；完整反事实收益对照由研究侧离线重放（同一入场流可随时从
     * `daily_profit_prediction_selection` + 日线全量重建），在线影子只负责逐日分歧可见性。
     */
    private fun logShadowDivergence(
        tradeDate: LocalDate,
        previousHoldings: List<DailyHoldingState>,
        newEntries: List<HoldingStateMachine.EntryCandidate>,
        tradingDaysSince: (LocalDate, LocalDate) -> Int,
        candleFor: (String, LocalDate) -> model.Candle?,
        prodResult: HoldingStateMachine.DayResult,
    ) {
        runCatching {
            val shadow = shadowStateMachine.advance(
                tradeDate = tradeDate,
                previousHoldings = previousHoldings,
                newEntries = newEntries,
                tradingDaysSince = tradingDaysSince,
                candleFor = candleFor,
            )
            val prodExits = prodResult.exited.toSet()
            val shadowExits = shadow.exited.toSet()
            val onlyProd = prodExits - shadowExits
            val onlyShadow = shadowExits - prodExits
            logger.info(
                "[影子对照] tradeDate=$tradeDate 生产tp8H3离场=${prodExits.size} 影子tp7H5离场=${shadowExits.size}" +
                    " 仅生产离场=${onlyProd.ifEmpty { setOf("无") }} 仅影子离场=${onlyShadow.ifEmpty { setOf("无") }}"
            )
        }.onFailure { logger.warning("[影子对照] 推演失败（不影响生产链路）| ${it.message}") }
    }

    /**
     * 将上一轮已落库的非空买点回填进本轮重算的目标仓位——仅对当前 limitPrice 为 null 的票生效，
     * 已带买点的票（理论上重算产出恒为 null，留作防御）不覆盖。
     *
     * 业务意义：全链重算 [replaceForDate] 是整行覆盖，会把 limit_price 抹回 null；本合并在覆盖前把旧买点
     * 带进新行，使 [AgentEntryBackfillStep] 的「跳过已有买点」幂等优化在跨重算场景生效（重跑只补缺失，
     * 不全量重跑 agent）。调用方须先经 [SelectionDriftGuard] 确认 selected 集合与历史一致，旧买点才对应同一只票。
     *
     * 纯函数无副作用，抽出便于单测覆盖「保留/不覆盖/无旧值/空快照」四种分支。
     */
    internal fun mergePriorLimitPrices(
        targets: List<TargetPosition>,
        priorLimitPrices: Map<String, Double>,
    ): List<TargetPosition> {
        if (priorLimitPrices.isEmpty()) return targets
        return targets.map { position ->
            if (position.limitPrice == null) {
                priorLimitPrices[position.tsCode]?.let { position.copy(limitPrice = it) } ?: position
            } else {
                position
            }
        }
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

    /**
     * 在升序交易日列表里二分查找 [target] 的精确下标；命中返回下标，未命中返回 -1。
     * 列表是区间内连续交易日，下标差即交易日间隔。
     */
    private fun binarySearchExact(sorted: List<LocalDate>, target: LocalDate): Int {
        var lo = 0
        var hi = sorted.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = sorted[mid].compareTo(target)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
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
