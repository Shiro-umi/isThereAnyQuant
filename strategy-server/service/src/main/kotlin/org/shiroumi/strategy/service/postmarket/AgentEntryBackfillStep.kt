package org.shiroumi.strategy.service.postmarket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDate
import model.PriceBasis
import org.shiroumi.agententry.AgentEntryBackfiller
import org.shiroumi.agententry.BackfillResult
import org.shiroumi.agententry.dailyPriceLimitPct
import org.shiroumi.config.AgentModelResolution
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.database.strategy.daily.repository.ProfitPredictionSelection
import utils.logger

private val logger by logger("AgentEntryBackfillStep")

/**
 * 盘后选股后的 agent 买点回填步——生产链路常驻一环，与手动 CLI `agent-entry-backfill` 同内核同口径。
 *
 * 业务定位：选股写库（[DailyProfitPredictionSelectionRepository.replaceForDate]）之后、持仓推进
 * （[PostMarketPreparationJob.advanceHoldings]）之前调用。对 target_date 的 selected 票按模型分降序取 Top-N，
 * 并发跑 agent 量价分析产出买点限价，回填 `limit_price`。买点同时生效于跟踪页展示与次日盘后 LIMIT 入场。
 *
 * 与 CLI 的唯一差异：CLI 是单日手动入口、自取 config/projectRoot；本 step 跑在盘后 suspend 链内，
 * 直接 suspend 调用 [AgentEntryBackfiller.backfillOne]（无 runBlocking 桥接），并把覆盖率结果交回
 * [PostMarketPreparationJob] 做阻断判定。
 *
 * 防未来函数：买点 as-of 锚 = 信号日（selection.tradeDate，= targetDate 的上一交易日），agent 看不到信号日之后数据。
 */
object AgentEntryBackfillStep {

    /**
     * 回填结果：期望填满槽位数 / 实际分析只数 / 成功获得有效买点只数。
     * 覆盖率 = filled / candidates，表征期望 Top-N 槽位中有多少只获得了可达买点。
     */
    data class Outcome(
        val candidates: Int,
        val attempted: Int,
        val filled: Int,
    ) {
        /** 候选为 0 时覆盖率视为 1.0（无票可回填不阻断）。 */
        val coverage: Double get() = if (candidates == 0) 1.0 else filled.toDouble() / candidates
    }

    /**
     * 单只回填器：跑 agent 分析单只票，返回 [BackfillResult]。抽成可注入函数便于测试替换。
     */
    fun interface SingleBackfill {
        suspend operator fun invoke(
            targetDate: LocalDate,
            signalDate: LocalDate,
            tsCode: String,
        ): BackfillResult
    }

    /**
     * 对 [targetDate] 的 selected Top-N 票回填买点，含买点涨跌停校验与顺位补充。
     *
     * 阶段一：取前 [config.topN] 只票，已有买点且有效的直接入池，其余并发 agent 分析。
     * 阶段二：校验买点是否在日涨跌停范围内可达（limitPrice >= close * (1 - limitPct)），
     * 超限的顺位递补分析，直至满额或无候选。
     *
     * @param config 回填配置（topN / parallelism / perStockTimeoutSec / modelKey）。
     * @param loadSelections 当日 selected 票供给器（已按模型分降序），默认查 DB；测试注入 fixture。
     * @param backfillOne 单只回填实现，默认走真 agent；测试注入 fake。
     * @return 覆盖率统计；候选为空时返回全零 Outcome（coverage=1.0）。
     */
    suspend fun backfill(
        targetDate: LocalDate,
        config: AgentEntryBackfillConfig,
        loadSelections: (LocalDate) -> List<ProfitPredictionSelection> = {
            DailyProfitPredictionSelectionRepository.findSelectionsByTargetDate(it)
        },
        backfillOne: SingleBackfill = defaultBackfillOne(config, targetDate),
    ): Outcome {
        // 全部 selected 票（已按模型分降序），不截断 topN——补充阶段需要超出 topN 的候选。
        // 按 tsCode 去重保留模型分最高的一条（同一只票重复出现为数据异常，防虚增 validPool）。
        val allSelections = loadSelections(targetDate).distinctBy { it.tsCode }
        if (allSelections.isEmpty()) {
            logger.info("[买点回填] target_date=$targetDate 无 selected 票，跳过")
            return Outcome(candidates = 0, attempted = 0, filled = 0)
        }

        // 信号日 = tradeDate（selection 行自带），加载全市场前复权收盘价用于买点可达性校验。
        val signalDate = allSelections.first().tradeDate
        val closePrices = loadClosePriceMap(signalDate)

        // 有效池：持有有效买点的票 (selection → limitPrice)。
        val validPool = mutableListOf<Pair<ProfitPredictionSelection, Double>>()
        // 初始分析批次：前 topN 中无买点或已有买点超限的票。
        val initialBatch = mutableListOf<ProfitPredictionSelection>()
        // 当前在 allSelections 中的扫描游标（初始批次末尾的后一位置）。
        var cursor = 0

        for (sel in allSelections) {
            if (initialBatch.size + validPool.size >= config.topN) break
            val existingLimit = sel.limitPrice
            if (existingLimit != null &&
                isBuyPointReachable(existingLimit, closePrices[sel.tsCode], sel.tsCode)
            ) {
                validPool.add(sel to existingLimit)
            } else {
                initialBatch.add(sel)
            }
            cursor++
        }

        val preExisting = validPool.size
        logger.info(
            "[买点回填] target_date=$targetDate 全部候选=${allSelections.size} " +
                "已有效买点=$preExisting 初始批次=${initialBatch.size} 并发=${config.parallelism}"
        )

        // 结构化并发：coroutineScope 继承盘后 suspend 链上下文，块结束时所有子协程已收束。
        val gate = Semaphore(config.parallelism)
        var totalAttempted = 0

        suspend fun analyzeBatch(batch: List<ProfitPredictionSelection>): Map<ProfitPredictionSelection, BackfillResult> {
            if (batch.isEmpty()) return emptyMap()
            return coroutineScope {
                batch.map { sel ->
                    async(Dispatchers.IO) {
                        val result = gate.withPermit {
                            try {
                                backfillOne(targetDate, sel.tradeDate, sel.tsCode)
                            } catch (e: CancellationException) {
                                throw e // 协程取消信号必须传播，不可吞掉
                            } catch (e: Exception) {
                                logger.warning("[买点回填] ${sel.tsCode} 异常：${e.message}")
                                BackfillResult(ok = false, limitPrice = null)
                            }
                        }
                        sel to result
                    }
                }.awaitAll().toMap()
            }
        }

        // 阶段一：并发分析初始批次。
        val round1 = analyzeBatch(initialBatch)
        totalAttempted += round1.size
        for ((sel, result) in round1) {
            val lp = result.limitPrice
            if (result.ok && lp != null &&
                isBuyPointReachable(lp, closePrices[sel.tsCode], sel.tsCode)
            ) {
                validPool.add(sel to lp)
            }
        }

        // 阶段二：顺位递补——每轮取缺少的只数，并发分析下一顺位候选。
        var round = 2
        while (validPool.size < config.topN && cursor < allSelections.size) {
            val needed = config.topN - validPool.size
            val supplementBatch = mutableListOf<ProfitPredictionSelection>()
            while (supplementBatch.size < needed && cursor < allSelections.size) {
                val next = allSelections[cursor]
                cursor++
                val existing = next.limitPrice
                if (existing != null &&
                    isBuyPointReachable(existing, closePrices[next.tsCode], next.tsCode)
                ) {
                    // 已有有效买点直接入池，与阶段一行为一致（补偿队列重跑场景生效）
                    validPool.add(next to existing)
                } else {
                    supplementBatch.add(next)
                }
            }

            if (supplementBatch.isEmpty()) break
            // validPool 可能在内层循环中因已有有效买点直接入池而达到 topN，
            // 此时不需再启动 agent 进程分析 supplementBatch。
            if (validPool.size >= config.topN) break

            logger.info("[买点回填] 第 $round 轮补充批次=${supplementBatch.size} 只（缺 $needed 只）")
            val suppResults = analyzeBatch(supplementBatch)
            totalAttempted += suppResults.size
            for ((sel, result) in suppResults) {
                val lp = result.limitPrice
                if (result.ok && lp != null &&
                    isBuyPointReachable(lp, closePrices[sel.tsCode], sel.tsCode)
                ) {
                    validPool.add(sel to lp)
                }
            }
            round++
        }

        val filled = validPool.size
        // candidates = min(期望槽位数, 实际候选池大小)，避免选股池不足 topN 时伪阻断
        // （例：只选到 3 只全成功，coverage=3/3 而非 3/5）
        val effectiveCandidates = minOf(config.topN, allSelections.size)
        logger.info(
            "[买点回填] target_date=$targetDate 完成：有效买点 $filled/$effectiveCandidates " +
                "（分析 $totalAttempted 只，缺买点 ${effectiveCandidates - filled} 只回退开盘价）"
        )
        return Outcome(candidates = effectiveCandidates, attempted = totalAttempted, filled = filled)
    }

    /**
     * 默认单只回填实现：从 config.yaml 解析模型与端口，委托共享脚手架 [AgentEntryBackfiller] 跑 agent +
     * 解析 + 写库。projectRoot 解析与 workspace 路径派生与 CLI `agent-entry-backfill` 同口径（共享内核）。
     */
    private fun defaultBackfillOne(config: AgentEntryBackfillConfig, targetDate: LocalDate): SingleBackfill {
        val quantConfig = ConfigManager.load()
        val serverPort = quantConfig.server.port
        val model = AgentModelResolution.resolve(quantConfig.agent, config.modelKey)
        val projectRoot = AgentEntryBackfiller.resolveProjectRoot()
        // workspace 仅随 targetDate 变化，整批 selected 票共用同一目录——构造时算一次，避免逐票重派生。
        val workspaceBase = AgentEntryBackfiller.workspaceFor(targetDate)
        logger.info(
            "[买点回填] 模型=${model.modelId ?: "默认"} provider=${model.provider} " +
                "server端口=$serverPort projectRoot=$projectRoot"
        )
        return SingleBackfill { _, signalDate, tsCode ->
            AgentEntryBackfiller.backfillOne(
                targetDate = targetDate,
                signalDate = signalDate,
                tsCode = tsCode,
                projectRoot = projectRoot,
                workspaceBase = workspaceBase,
                serverPort = serverPort,
                model = model,
                perStockTimeoutSec = config.perStockTimeoutSec,
                onLog = { logger.info("[买点回填]   $it") },
            )
        }
    }

    /**
     * 加载指定交易日全市场前复权收盘价映射（tsCode → closeQfq）。
     *
     * closeQfq ≤ 0 时回退不复权 close，与 [model.Candle.price] QFQ 口径一致。
     * 收盘价缺失的股票不在映射中（校验时放行）。
     */
    private fun loadClosePriceMap(tradeDate: LocalDate): Map<String, Double> {
        return try {
            StockDailyCandleRepository.findByTradeDate(tradeDate)
                .associate { candle ->
                    candle.tsCode to candle.price(PriceBasis.QFQ)
                }
                .also { m ->
                    if (m.isEmpty()) {
                        logger.warning("[买点回填] $tradeDate 收盘价数据为空，所有买点校验放行（日线数据可能尚未同步）")
                    }
                }
        } catch (e: Exception) {
            logger.warning("[买点回填] 加载 $tradeDate 收盘价失败：${e.message}")
            emptyMap()
        }
    }

    /**
     * 判定 agent 买点是否在日涨跌停范围内可达。
     *
     * 校验逻辑：limitPrice >= close * (1 - dailyPriceLimitPct)。
     * closePrice 为 null 或 ≤0 时放行（数据缺失不做校验，避免误伤）。
     */
    private fun isBuyPointReachable(
        limitPrice: Double,
        closePrice: Double?,
        tsCode: String,
    ): Boolean {
        if (closePrice == null || closePrice <= 0.0) return true
        val limitPct = dailyPriceLimitPct(tsCode)
        val lowerBound = closePrice * (1.0 - limitPct)
        if (limitPrice < lowerBound) {
            logger.info(
                "[买点回填]   $tsCode 买点=$limitPrice 低于跌停下界=$lowerBound " +
                    "（收盘=$closePrice 幅度=$limitPct），顺位递补"
            )
            return false
        }
        return true
    }
}
