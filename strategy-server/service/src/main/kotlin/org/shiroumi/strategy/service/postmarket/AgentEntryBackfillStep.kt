package org.shiroumi.strategy.service.postmarket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDate
import org.shiroumi.agententry.AgentEntryBackfiller
import org.shiroumi.config.AgentModelResolution
import org.shiroumi.config.ConfigManager
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

    /** 回填结果：候选数 / 待跑数（剔除已存在）/ 成功回填数。覆盖率 = filled / candidates。 */
    data class Outcome(
        val candidates: Int,
        val attempted: Int,
        val filled: Int,
    ) {
        /** 候选为 0 时覆盖率视为 1.0（无票可回填不阻断）。 */
        val coverage: Double get() = if (candidates == 0) 1.0 else filled.toDouble() / candidates
    }

    /**
     * 单只回填器：跑 agent 分析单只票，成功且解析出买点则回填 DB 返回 true。抽成可注入函数便于测试替换。
     */
    fun interface SingleBackfill {
        suspend operator fun invoke(
            targetDate: LocalDate,
            signalDate: LocalDate,
            tsCode: String,
        ): Boolean
    }

    /**
     * 对 [targetDate] 的 selected Top-N 票回填买点。
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
        // 当日 selected 票（已按模型分降序），取前 topN——即生产实盘等权 Top3 的入场候选。
        val selections = loadSelections(targetDate).take(config.topN)
        if (selections.isEmpty()) {
            logger.info("[买点回填] target_date=$targetDate 无 selected 票，跳过")
            return Outcome(candidates = 0, attempted = 0, filled = 0)
        }

        // 已有买点的票跳过，只补缺失（盘后重跑幂等：已回填的不重复跑 agent）。
        val tasks = selections.filter { it.limitPrice == null }
        val alreadyFilled = selections.size - tasks.size
        logger.info(
            "[买点回填] target_date=$targetDate 候选=${selections.size} 已有买点=$alreadyFilled " +
                "待跑=${tasks.size} 并发=${config.parallelism}"
        )
        if (tasks.isEmpty()) {
            return Outcome(candidates = selections.size, attempted = 0, filled = selections.size)
        }

        // 结构化并发：coroutineScope 继承盘后 suspend 链上下文，块结束时所有子协程已收束，无游离 scope；
        // 单只 runCatching 兜底，任一只失败/异常不取消其他只（记为未回填，由覆盖率阈值统一裁决）。
        val gate = Semaphore(config.parallelism)
        val newlyFilled = coroutineScope {
            tasks.map { selection ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        runCatching {
                            backfillOne(targetDate, selection.tradeDate, selection.tsCode)
                        }.getOrElse { e ->
                            logger.warning("[买点回填] ${selection.tsCode} 异常：${e.message}")
                            false
                        }
                    }
                }
            }.awaitAll().count { it }
        }

        val filled = alreadyFilled + newlyFilled
        logger.info(
            "[买点回填] target_date=$targetDate 完成：回填 $filled/${selections.size} " +
                "（本轮新增 $newlyFilled，缺买点 ${selections.size - filled} 只回退开盘价）"
        )
        return Outcome(candidates = selections.size, attempted = tasks.size, filled = filled)
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
}
