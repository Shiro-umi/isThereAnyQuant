package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.strategy.core.daily.TargetPosition
import utils.logger

private val selectionDriftLogger by logger("SelectionDriftGuard")

/**
 * selection 复现守卫——全链重建覆盖 `daily_profit_prediction_selection` 前的逐票一致性闸门。
 *
 * 全链重算会用本次推理产出的 [TargetPosition] 覆盖历史落库 selection。覆盖前，本守卫把重算结果与
 * 历史落库逐票（tsCode + 排序）比对：
 * - 一致：放行覆盖（重算复现历史链，写库无副作用）。
 * - 不一致：默认拒绝覆盖并抛 [SelectionDriftException]，避免静默改写历史选股链使下游持仓链分叉。
 *
 * 比对口径锁定为「selected 票按模型分降序、同分按 tsCode 升序」——与
 * [DailyProfitPredictionSelectionRepository.findSelectionsByTradeDates] 落库读取排序、与
 * [org.shiroumi.strategy.service.model.ProfitPredictionModelSelector.generateTargets] 的
 * `rankedPredictions`（score DESC, tsCode ASC）排序同源，保证比对的是同一执行序。
 *
 * 放行漂移开关：`-Dquant.strategy.rebuild.allowSelectionDrift=true`。开启时漂移只告警不阻断，
 * 允许「换模型/换候选口径」的有意重建落库。默认 false。
 *
 * 历史无落库（首次重建该日）视为无可比对基准，直接放行。
 */
object SelectionDriftGuard {

    private const val ALLOW_DRIFT_PROPERTY = "quant.strategy.rebuild.allowSelectionDrift"

    /** 漂移放行开关：仅当 `quant.strategy.rebuild.allowSelectionDrift=true` 时允许漂移落库。默认 false。 */
    fun allowDrift(): Boolean =
        System.getProperty(ALLOW_DRIFT_PROPERTY, "false").toBoolean()

    /**
     * 断言重算 selection 与历史落库逐票一致；不一致时按开关决策放行或抛错。
     *
     * @param tradeDate 选股日（落库 trade_date 维度）
     * @param recomputed 本次重算产出的全量目标仓位（含未选中票，内部只取 selected 子序列比对）
     * @throws SelectionDriftException 当漂移且未显式放行时抛出，阻断覆盖写库。
     */
    fun assertReproducible(tradeDate: LocalDate, recomputed: List<TargetPosition>) {
        val historical = DailyProfitPredictionSelectionRepository
            .findSelectionsByTradeDates(listOf(tradeDate))[tradeDate]
            .orEmpty()
            .map { it.tsCode }
        if (historical.isEmpty()) {
            selectionDriftLogger.info(
                "[selection复现] tradeDate=$tradeDate 历史无落库基准，跳过比对直接覆盖"
            )
            return
        }

        val recomputedSelected = recomputed
            .filter { it.selected }
            .sortedWith(compareByDescending<TargetPosition> { it.selectionScore }.thenBy { it.tsCode })
            .map { it.tsCode }

        if (recomputedSelected == historical) {
            selectionDriftLogger.info(
                "[selection复现] tradeDate=$tradeDate 逐票一致(${historical.size}只)，复现通过"
            )
            return
        }

        val diff = describeDiff(historical = historical, recomputed = recomputedSelected)
        if (allowDrift()) {
            selectionDriftLogger.warning(
                "[selection复现] tradeDate=$tradeDate 漂移已显式放行($ALLOW_DRIFT_PROPERTY=true)，覆盖落库 | $diff"
            )
            return
        }
        selectionDriftLogger.error(
            "[selection复现] tradeDate=$tradeDate 重算与历史落库不一致，拒绝覆盖 | $diff"
        )
        throw SelectionDriftException(tradeDate = tradeDate, detail = diff)
    }

    /** 逐票差异描述：历史/重算各自有序序列 + 各自独有票，供日志与异常消息直接呈现执行序分歧。 */
    private fun describeDiff(historical: List<String>, recomputed: List<String>): String {
        val onlyHistorical = historical - recomputed.toSet()
        val onlyRecomputed = recomputed - historical.toSet()
        return "历史=$historical 重算=$recomputed " +
            "仅历史=${onlyHistorical.ifEmpty { listOf("无") }} 仅重算=${onlyRecomputed.ifEmpty { listOf("无") }}"
    }
}

/**
 * selection 复现失败异常——重算结果与历史落库不一致且未显式放行漂移时抛出，阻断覆盖写库。
 */
class SelectionDriftException(
    val tradeDate: LocalDate,
    val detail: String,
) : IllegalStateException(
    "selection 复现失败 tradeDate=$tradeDate（重算与历史落库不一致）。" +
        "确需按新口径重建请显式传 -Dquant.strategy.rebuild.allowSelectionDrift=true。明细：$detail"
)
