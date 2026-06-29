package org.shiroumi.backtest.feed

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.shiroumi.backtest.domain.StrategyDecision

/**
 * 外部 EMA20 趋势池 Top5 选股清单决策 feed。
 *
 * 业务背景：实盘交易路径的选股源是「主板 + EMA20 斜率前 N 池内，模型分 Top5」。这批选股事实在
 * 线下离线产出（见 research/exit-policy ema20_pool），不落 `daily_profit_prediction_selection` 库表，
 * 因此回测需要一个能直接吃外部清单、产出与库表口径同构 [StrategyDecision.TargetPortfolioDecision]
 * 的选股源，替代默认的 [PreloadedDecisionFeed]（读库表）。
 *
 * 边界纪律（回测是执行层，不重新选股）：
 *  - 本 feed 只承载「已在线下确认的选股事实」，不重新打分、不重新排序、不运行任何选股算法。
 *  - 清单里每个信号日的标的已按 modelScore 降序排列；本 feed 原样保序填入 `targetWeights`
 *    （[LinkedHashMap] 保插入序），使 [org.shiroumi.backtest.engine.EntryGatekeeper] 在
 *    `entryOrdering=MODEL_SCORE` 下取原始顺序即等价「模型分 Top N 入场」，与生产口径对齐。
 *  - `targetWeights` 等权 `1/N`，与 [PreloadedDecisionFeed] 的等权口径一致。
 *  - `StrategyDecision` 不含任何账户字段。
 *
 * 防未来函数口径（与 [AgentEntryPriceFeed] / [DecisionFileExporter] 严格一致）：
 *  - 清单里的 `signalDate` 是信号日 T（盘后选股发生日）。回测决策的 `effectiveDate` 是执行日 T+1
 *    （开盘建仓日）。本 feed 在构造时把每个 `signalDate` 经 [nextTradingDay] 映射为日历上的下一交易日
 *    作为 `effectiveDate`，使「T 盘后选股、T+1 开盘执行」的时间对齐成立，杜绝用 T+1 及之后的信息选股。
 *  - 同一执行日只产出一个 [StrategyDecision.TargetPortfolioDecision]。
 *
 * @param selectionFile 外部选股清单 JSON 路径，结构见 [Ema20PoolSelectionFile]。
 * @param nextTradingDay 信号日 T → 下一交易日 T+1 的映射（装配方注入 calendar repository，feed 零 DB 依赖、可单测）。
 * @param json 反序列化器。
 */
class Ema20PoolDecisionFeed(
    selectionFile: Path,
    nextTradingDay: (LocalDate) -> LocalDate?,
    json: Json = LenientJson,
) : StrategyDecisionFeed {

    private val byExecutionDate: Map<LocalDate, List<StrategyDecision>>

    init {
        val text = Files.readString(selectionFile)
        val decoded = json.decodeFromString<Ema20PoolSelectionFile>(text)

        // 先按 signalDate 聚合，组内保持清单原始顺序（清单已按 modelScore 降序）。
        val bySignalDate = LinkedHashMap<LocalDate, MutableList<Ema20PoolPick>>()
        for (pick in decoded.picks) {
            val signalDate = LocalDate.parse(pick.signalDate)
            bySignalDate.getOrPut(signalDate) { mutableListOf() }.add(pick)
        }

        val decisions = mutableListOf<StrategyDecision>()
        for ((signalDate, picks) in bySignalDate) {
            // 防未来函数：信号日 T 盘后选股，映射到 T+1 开盘执行日。
            val executionDate = nextTradingDay(signalDate) ?: continue
            if (picks.isEmpty()) continue

            // 按清单已携带的 modelScore 降序排序（稳定排序，同分按 tsCode），与库表口径
            // PreloadedDecisionFeed 一致。这不是「重新打分」——只用清单已有的分做确定性排序，
            // 把正确性从「靠上游清单自觉降序」改为「靠 feed 自身兜底」，杜绝上游排序漂移时静默选错股。
            // 排序后保序去重（同信号日同标的保留首个/最高分），闸门 MODEL_SCORE 取原始顺序即 Top N。
            val orderedTsCodes = picks
                .sortedWith(compareByDescending<Ema20PoolPick> { it.modelScore }.thenBy { it.tsCode })
                .mapTo(LinkedHashSet()) { it.tsCode }
            if (orderedTsCodes.isEmpty()) continue

            // 权重分母用去重后只数，保证权重和恒为 1。
            val weight = 1.0 / orderedTsCodes.size
            val targetWeights = LinkedHashMap<String, Double>()
            for (tsCode in orderedTsCodes) targetWeights[tsCode] = weight

            decisions += StrategyDecision.TargetPortfolioDecision(
                effectiveDate = executionDate,
                reason = "ema20_pool selection signalDate=$signalDate rows=${targetWeights.size}",
                targetWeights = targetWeights,
                sentimentExposure = 0.0,
            )
        }

        byExecutionDate = decisions.groupBy { it.effectiveDate }
    }

    override fun decisionsFor(date: LocalDate): List<StrategyDecision> = byExecutionDate[date].orEmpty()

    private companion object {
        /** 容忍清单里 pick 携带 feed 不消费的额外字段（ema20Slope10 / rank 等）。 */
        val LenientJson: Json = Json { ignoreUnknownKeys = true }
    }
}

/** 外部 EMA20 池选股清单文件结构。仅 [picks] 被消费；window/pool/count 等元数据忽略。 */
@Serializable
internal data class Ema20PoolSelectionFile(
    val picks: List<Ema20PoolPick>,
)

/**
 * 单条选股记录。
 *
 * @param signalDate 信号日 T（YYYY-MM-DD），盘后选股发生日。
 * @param tsCode 标的代码（带交易所后缀）。
 * @param modelScore 模型分；清单已按此降序排列，feed 只依赖顺序不读取数值。
 */
@Serializable
internal data class Ema20PoolPick(
    val signalDate: String,
    val tsCode: String,
    val modelScore: Double = 0.0,
)
