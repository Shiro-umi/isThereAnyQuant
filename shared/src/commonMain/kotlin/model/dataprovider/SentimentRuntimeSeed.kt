package model.dataprovider

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * 盘中情绪推演使用的完整滚动状态种子。
 *
 * 设计目标非常明确：
 * 1. 盘后统一计算完成后，把“次日盘中继续滚动所需的完整状态”一次性固化下来
 * 2. 盘中实时增量阶段不再重新抽样、不再重新构造历史窗口、不再推测 state
 * 3. 保证盘中推演和盘后真实情绪计算使用的是同一批样本股、同一份滚动状态
 *
 * 字段语义：
 * - `scope`：情绪作用域，当前第一阶段使用 `main_board`
 * - `forTradeDate`：这份种子将被哪个交易日的盘中增量推演使用
 * - `sourceTradeDate`：这份种子由哪个交易日盘后生成
 * - `sampleCodes`：盘中全日固定使用的样本股列表，绝不允许盘中重新抽样
 * - `symbolStates`：每只样本股 EMA / 收益窗口的完整滚动状态
 * - `bullRatioHistory / marketVolHistory / accelHistory / combinedHistory`：市场级滚动历史
 * - `totalDays`：累计推进天数，参与 sufficientHistory 语义
 *
 * 这份 seed 不再是“近似恢复窗口”，而是“完整恢复前一交易日 MarketSentimentRollingState”。
 */
@Serializable
data class SentimentSymbolStateSeed(
    val tsCode: String,
    val emaShort: Double,
    val emaLong: Double,
    val prevClose: Double,
    val recentReturns: List<Double>,
    val nextReturnIndex: Int,
    val returnWindowSize: Int,
    val returnSum: Double,
    val returnSumSq: Double,
)

@Serializable
data class SentimentRuntimeSeed(
    val scope: String,
    val forTradeDate: LocalDate,
    val sourceTradeDate: LocalDate,
    val signalBasis: String,
    val requiredHistory: Int,
    val sampleSize: Int,
    val sampleCodes: List<String>,
    val symbolStates: List<SentimentSymbolStateSeed>,
    val bullRatioHistory: List<Double>,
    val marketVolHistory: List<Double>,
    val accelHistory: List<Double>,
    val combinedHistory: List<Double>,
    val totalDays: Int,
    val createdAt: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
)

/**
 * 当前新架构内置的情绪作用域常量。
 *
 * 第一阶段先只支持主板情绪。
 * 后续如果扩展到全市场或策略股票池，也应继续沿用这个常量集中管理。
 */
object SentimentScopes {
    const val MAIN_BOARD = "main_board"
}
