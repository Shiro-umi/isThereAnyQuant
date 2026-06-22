package org.shiroumi.strategy.service.postmarket

/**
 * 盘后选股后自动回填 agent 买点的配置（系统属性口径，与 [HoldingStateMachine.ExitRules.fromSystemProperties]
 * 同范式）。全部缺省 = 自动回填开启、Top5 全成功才放行。
 *
 * 业务定位：选股写库后、持仓推进前，对 target_date 的 selected Top-N 票并发跑 agent 量价分析产出买点限价，
 * 回填 `daily_profit_prediction_selection.limit_price`。默认 Top-N = 5，即回填全部 selected 票，
 * 前端选股结果页展示全部 5 只买入价；实际是否入场由次日盘后 LIMIT 触达判定（当日股价触碰买点才建仓），
 * 是否买入与回填几只解耦。
 *
 * 阻断语义：回填覆盖率低于 [minCoverage] 时由 [AgentEntryBackfillStep] 抛
 * [AgentEntryBackfillInsufficientCoverageException]，盘后链路失败 → 该日不标 strategyUpdated →
 * 走现成补偿队列指数退避重跑（与其他盘后失败同一出口）。
 *
 * 人工出口：[enabled]=false 关闭自动回填，盘后恢复到「写 selection 但不跑 agent、不阻断」，
 * 买点为空回退手动 CLI `agent-entry-backfill`。这是 agent 运行时不可用时的硬降级开关。
 */
data class AgentEntryBackfillConfig(
    val enabled: Boolean,
    val minCoverage: Double,
    val topN: Int,
    val parallelism: Int,
    val perStockTimeoutSec: Long,
    val modelKey: String?,
) {
    companion object {
        /**
         * 从系统属性装配，缺省 = 开启 / 覆盖率 1.0（Top5 全成功）/ Top5 / 并发 5 / 单只 300s / 默认模型。
         *
         * - `quant.strategy.entryBackfill.enabled`         默认 true
         * - `quant.strategy.entryBackfill.minCoverage`     默认 1.0（[0,1]，1.0=全成功才放行）
         * - `quant.strategy.entryBackfill.topN`            默认 5（回填全部 selected 票，前端展示 5 只买入价）
         * - `quant.strategy.entryBackfill.parallelism`     默认 5（每只独立 ACP 子进程，与 topN 对齐避免串行排队）
         * - `quant.strategy.entryBackfill.perStockTimeoutSec` 默认 300
         * - `quant.strategy.entryBackfill.modelKey`        默认空 → 回退 config.yaml agent.defaultModelKey
         */
        fun fromSystemProperties(): AgentEntryBackfillConfig {
            fun prop(name: String): String? =
                System.getProperty("quant.strategy.entryBackfill.$name")?.takeIf { it.isNotBlank() }

            return AgentEntryBackfillConfig(
                enabled = prop("enabled")?.toBooleanStrictOrNull() ?: true,
                minCoverage = prop("minCoverage")?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0,
                topN = prop("topN")?.toIntOrNull()?.coerceAtLeast(1) ?: 5,
                parallelism = prop("parallelism")?.toIntOrNull()?.coerceAtLeast(1) ?: 5,
                perStockTimeoutSec = prop("perStockTimeoutSec")?.toLongOrNull()?.coerceAtLeast(1) ?: 300,
                modelKey = prop("modelKey"),
            )
        }
    }
}

/**
 * 回填覆盖率不足触发的盘后阻断异常。
 *
 * 由 [AgentEntryBackfillStep] 抛出，[PostMarketOrchestrator.executeTradeDatesCatching] 捕获后置该日 failedDate
 * → 该日不标 strategyUpdated → 上游补偿队列重跑。重跑时 selection 由 [SelectionDriftGuard] 保证可复现幂等，
 * `updateLimitPrice` 覆盖写幂等；重跑前 [PostMarketPreparationJob] 通过 `findLimitPricesByTradeDate` 保留
 * 上一轮已成功的买点（避免被整行覆盖抹回 null），故重跑只补失败的票、不全量重跑 agent，整体安全且省算力。
 */
class AgentEntryBackfillInsufficientCoverageException(message: String) : IllegalStateException(message)
