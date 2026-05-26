package org.shiroumi.quant_kmp.algorithm.adjustment

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 除权除息事件类型
 * 表示股票发生的各种权益变动事件
 */
@Serializable
sealed class AdjustmentEvent {
    abstract val date: LocalDate
    abstract val description: String

    /**
     * 分红派息
     * @param cashPerShare 每股派息金额（元）
     */
    @Serializable
    data class Dividend(
        override val date: LocalDate,
        val cashPerShare: Double,
        override val description: String = "分红派息"
    ) : AdjustmentEvent()

    /**
     * 送股/转增股
     * @param ratio 送转比例（如10送3，ratio=0.3）
     */
    @Serializable
    data class StockSplit(
        override val date: LocalDate,
        val ratio: Double,
        override val description: String = "送股/转增"
    ) : AdjustmentEvent()

    /**
     * 配股
     * @param ratio 配股比例（如10配3，ratio=0.3）
     * @param price 配股价格（元）
     */
    @Serializable
    data class RightsIssue(
        override val date: LocalDate,
        val ratio: Double,
        val price: Double,
        override val description: String = "配股"
    ) : AdjustmentEvent()

    /**
     * 综合除权除息事件（同时包含分红、送股、配股）
     */
    @Serializable
    data class Composite(
        override val date: LocalDate,
        val dividend: Double = 0.0,
        val stockSplitRatio: Double = 0.0,
        val rightsIssueRatio: Double = 0.0,
        val rightsIssuePrice: Double = 0.0,
        override val description: String = "除权除息"
    ) : AdjustmentEvent()
}

/**
 * 复权因子
 * 用于记录某一时间点的价格调整系数
 *
 * @param date 日期
 * @param forwardFactor 前复权因子（相对于最新价格）
 * @param backwardFactor 后复权因子（相对于历史价格）
 * @param event 触发该因子的除权除息事件
 */
@Serializable
data class AdjustmentFactor(
    val date: LocalDate,
    val forwardFactor: Double,
    val backwardFactor: Double,
    val event: AdjustmentEvent? = null
)

/**
 * 复权类型
 */
enum class AdjustmentType {
    /**
     * 前复权：以最新价格为基准，调整历史价格
     * 特点：当前价格不变，历史价格被调整
     * 用途：技术分析、计算收益率
     */
    FORWARD,

    /**
     * 后复权：以历史价格为基准，调整后续价格
     * 特点：历史价格不变，后续价格被调整
     * 用途：计算长期收益率、资产总值
     */
    BACKWARD
}

/**
 * 可复权的K线数据接口
 * 任何需要复权计算的数据结构都应实现此接口
 */
interface AdjustableCandle {
    val date: LocalDate
    val open: Double
    val high: Double
    val low: Double
    val close: Double
    val volume: Double
    val amount: Double

    /**
     * 创建一个新的实例，应用复权后的价格
     */
    fun withAdjustedPrices(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double,
        amount: Double
    ): AdjustableCandle
}

/**
 * 标准K线数据类
 */
@Serializable
data class OhlcvCandle(
    override val date: LocalDate,
    override val open: Double,
    override val high: Double,
    override val low: Double,
    override val close: Double,
    override val volume: Double,
    override val amount: Double = 0.0,
    val symbol: String = ""
) : AdjustableCandle {

    override fun withAdjustedPrices(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double,
        amount: Double
    ): OhlcvCandle = copy(
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        amount = amount
    )

    /**
     * 转换为Float版本（用于前端展示）
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        open.toFloat(),
        high.toFloat(),
        low.toFloat(),
        close.toFloat(),
        volume.toFloat()
    )
}

/**
 * 复权结果包装类
 */
data class AdjustmentResult<T : AdjustableCandle>(
    val candles: List<T>,
    val factors: List<AdjustmentFactor>,
    val type: AdjustmentType
)

/**
 * 复权计算器
 * 核心算法实现
 */
object AdjustmentCalculator {

    // 精度常量
    private const val EPSILON = 1e-10
    private const val DECIMAL_PLACES = 6

    /**
     * 计算复权因子
     * 时间复杂度：O(n)，其中n为事件数量
     *
     * @param events 除权除息事件列表（按日期升序排列）
     * @param latestClose 最新收盘价（用于前复权计算）
     * @return 复权因子列表
     */
    fun calculateFactors(
        events: List<AdjustmentEvent>,
        latestClose: Double
    ): List<AdjustmentFactor> {
        if (events.isEmpty()) return emptyList()

        // 按日期升序排序
        val sortedEvents = events.sortedBy { it.date }

        // 计算累积复权因子
        var cumulativeForwardFactor = 1.0
        var cumulativeBackwardFactor = 1.0

        return sortedEvents.map { event ->
            val factor = when (event) {
                is AdjustmentEvent.Dividend -> {
                    calculateDividendFactor(event, cumulativeForwardFactor, cumulativeBackwardFactor, latestClose)
                }
                is AdjustmentEvent.StockSplit -> {
                    calculateStockSplitFactor(event, cumulativeForwardFactor, cumulativeBackwardFactor)
                }
                is AdjustmentEvent.RightsIssue -> {
                    calculateRightsIssueFactor(event, cumulativeForwardFactor, cumulativeBackwardFactor, latestClose)
                }
                is AdjustmentEvent.Composite -> {
                    calculateCompositeFactor(event, cumulativeForwardFactor, cumulativeBackwardFactor, latestClose)
                }
            }

            cumulativeForwardFactor = factor.forwardFactor
            cumulativeBackwardFactor = factor.backwardFactor

            factor
        }
    }

    /**
     * 计算分红派息的复权因子
     *
     * 前复权因子 = 1 - 分红金额/参考价格
     * 后复权因子 = 参考价格 / (参考价格 - 分红金额)
     */
    private fun calculateDividendFactor(
        event: AdjustmentEvent.Dividend,
        currentForwardFactor: Double,
        currentBackwardFactor: Double,
        referencePrice: Double
    ): AdjustmentFactor {
        // 使用参考价格计算分红影响
        // 前复权因子：价格降低的比例
        val dividendImpact = event.cashPerShare / referencePrice
        val singleForwardFactor = 1.0 - dividendImpact

        // 后复权因子：价格升高的比例（倒数关系）
        val singleBackwardFactor = 1.0 / singleForwardFactor

        return AdjustmentFactor(
            date = event.date,
            forwardFactor = (currentForwardFactor * singleForwardFactor).roundToPrecision(),
            backwardFactor = (currentBackwardFactor * singleBackwardFactor).roundToPrecision(),
            event = event
        )
    }

    /**
     * 计算送股/转增的复权因子
     *
     * 送转股后，股数增加，每股价格相应降低
     * 前复权因子 = 1 / (1 + 送转比例)
     * 后复权因子 = 1 + 送转比例
     */
    private fun calculateStockSplitFactor(
        event: AdjustmentEvent.StockSplit,
        currentForwardFactor: Double,
        currentBackwardFactor: Double
    ): AdjustmentFactor {
        val splitFactor = 1.0 + event.ratio

        // 前复权：价格降低
        val singleForwardFactor = 1.0 / splitFactor
        // 后复权：价格升高
        val singleBackwardFactor = splitFactor

        return AdjustmentFactor(
            date = event.date,
            forwardFactor = (currentForwardFactor * singleForwardFactor).roundToPrecision(),
            backwardFactor = (currentBackwardFactor * singleBackwardFactor).roundToPrecision(),
            event = event
        )
    }

    /**
     * 计算配股的复权因子
     *
     * 配股价格通常低于市价，需要计算理论除权价
     * 理论除权价 = (原股数 * 市价 + 配股数 * 配股价) / (原股数 + 配股数)
     * 复权因子 = 理论除权价 / 市价
     */
    private fun calculateRightsIssueFactor(
        event: AdjustmentEvent.RightsIssue,
        currentForwardFactor: Double,
        currentBackwardFactor: Double,
        referencePrice: Double
    ): AdjustmentFactor {
        // 理论除权价计算
        // 假设原持有1股，配股比例为ratio，则：
        // 理论除权价 = (1 * 市价 + ratio * 配股价) / (1 + ratio)
        val theoreticalPrice = (referencePrice + event.ratio * event.price) / (1.0 + event.ratio)

        // 前复权因子 = 理论除权价 / 市价（小于1）
        val singleForwardFactor = theoreticalPrice / referencePrice
        // 后复权因子 = 市价 / 理论除权价（大于1）
        val singleBackwardFactor = referencePrice / theoreticalPrice

        return AdjustmentFactor(
            date = event.date,
            forwardFactor = (currentForwardFactor * singleForwardFactor).roundToPrecision(),
            backwardFactor = (currentBackwardFactor * singleBackwardFactor).roundToPrecision(),
            event = event
        )
    }

    /**
     * 计算综合除权除息的复权因子
     */
    private fun calculateCompositeFactor(
        event: AdjustmentEvent.Composite,
        currentForwardFactor: Double,
        currentBackwardFactor: Double,
        referencePrice: Double
    ): AdjustmentFactor {
        // 综合计算：先处理送股，再处理配股，最后处理分红
        var forwardMultiplier = 1.0
        var backwardMultiplier = 1.0

        // 1. 送股/转增
        if (event.stockSplitRatio > EPSILON) {
            val splitFactor = 1.0 + event.stockSplitRatio
            forwardMultiplier *= (1.0 / splitFactor)
            backwardMultiplier *= splitFactor
        }

        // 2. 配股
        if (event.rightsIssueRatio > EPSILON && event.rightsIssuePrice > EPSILON) {
            val theoreticalPrice = (referencePrice + event.rightsIssueRatio * event.rightsIssuePrice) /
                    (1.0 + event.rightsIssueRatio)
            forwardMultiplier *= (theoreticalPrice / referencePrice)
            backwardMultiplier *= (referencePrice / theoreticalPrice)
        }

        // 3. 分红（在送配股后的基础上）
        if (event.dividend > EPSILON) {
            val dividendImpact = event.dividend / referencePrice
            val dividendFactor = 1.0 - dividendImpact
            forwardMultiplier *= dividendFactor
            backwardMultiplier *= (1.0 / dividendFactor)
        }

        return AdjustmentFactor(
            date = event.date,
            forwardFactor = (currentForwardFactor * forwardMultiplier).roundToPrecision(),
            backwardFactor = (currentBackwardFactor * backwardMultiplier).roundToPrecision(),
            event = event
        )
    }

    /**
     * 应用前复权
     * 时间复杂度：O(n + m)，其中n为K线数量，m为事件数量
     *
     * @param candles K线数据列表（按日期升序排列）
     * @param factors 复权因子列表（按日期升序排列）
     * @return 前复权后的K线数据
     */
    fun <T : AdjustableCandle> applyForwardAdjustment(
        candles: List<T>,
        factors: List<AdjustmentFactor>
    ): List<T> {
        if (candles.isEmpty() || factors.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            return candles as List<T>
        }

        // 前复权：以最新价格为基准，历史价格需要调整
        // 对于日期D的价格，如果D之后有除权除息事件，则历史价格需要乘以这些事件的forwardFactor
        return candles.map { candle ->
            // 找到该日期之后的所有因子（不包括当天，因为当天的价格已经是除权后的价格）
            val futureFactors = factors.filter { it.date > candle.date }

            // 累积因子 = 所有未来因子的乘积（使用forwardFactor）
            val cumulativeFactor = if (futureFactors.isEmpty()) {
                1.0
            } else {
                futureFactors.map { it.forwardFactor }.reduce { acc, factor -> acc * factor }
            }

            @Suppress("UNCHECKED_CAST")
            applyFactor(candle, cumulativeFactor, AdjustmentType.FORWARD) as T
        }
    }

    /**
     * 应用后复权
     * 时间复杂度：O(n + m)，其中n为K线数量，m为事件数量
     *
     * @param candles K线数据列表（按日期升序排列）
     * @param factors 复权因子列表（按日期升序排列）
     * @return 后复权后的K线数据
     */
    fun <T : AdjustableCandle> applyBackwardAdjustment(
        candles: List<T>,
        factors: List<AdjustmentFactor>
    ): List<T> {
        if (candles.isEmpty() || factors.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            return candles as List<T>
        }

        // 后复权：从历史价格正推，未来价格需要乘以累积因子
        // 对于日期D的价格，使用D及之前的所有因子的乘积
        return candles.map { candle ->
            // 找到该日期及之前的所有因子
            val pastFactors = factors.filter { it.date <= candle.date }

            // 累积因子 = 所有历史因子的乘积
            val cumulativeFactor = if (pastFactors.isEmpty()) {
                1.0
            } else {
                pastFactors.map { it.backwardFactor }.reduce { acc, factor -> acc * factor }
            }

            @Suppress("UNCHECKED_CAST")
            applyFactor(candle, cumulativeFactor, AdjustmentType.BACKWARD) as T
        }
    }

    /**
     * 应用复权因子到单根K线
     */
    private fun applyFactor(
        candle: AdjustableCandle,
        factor: Double,
        type: AdjustmentType
    ): AdjustableCandle {
        // 价格调整：乘以复权因子
        val adjustedOpen = (candle.open * factor).roundToPrecision()
        val adjustedHigh = (candle.high * factor).roundToPrecision()
        val adjustedLow = (candle.low * factor).roundToPrecision()
        val adjustedClose = (candle.close * factor).roundToPrecision()

        // 成交量调整：前复权时成交量需要除以因子（保持金额不变）
        // 后复权时成交量保持不变
        val adjustedVolume = when (type) {
            AdjustmentType.FORWARD -> (candle.volume / factor).roundToPrecision()
            AdjustmentType.BACKWARD -> candle.volume
        }

        // 成交额保持不变（价格 * 数量 = 金额，已经平衡）
        val adjustedAmount = adjustedClose * adjustedVolume

        return candle.withAdjustedPrices(
            open = adjustedOpen,
            high = adjustedHigh,
            low = adjustedLow,
            close = adjustedClose,
            volume = adjustedVolume,
            amount = adjustedAmount
        )
    }

    /**
     * 高精度四舍五入
     */
    private fun Double.roundToPrecision(): Double {
        if (this.isNaN() || this.isInfinite()) return this
        val multiplier = 10.0.pow(DECIMAL_PLACES)
        return (this * multiplier).roundToLong() / multiplier
    }
}

/**
 * 公共扩展函数：将Double四舍五入到指定小数位
 * 用于Kotlin Multiplatform兼容（替代String.format）
 *
 * @param decimals 小数位数
 * @return 四舍五入后的值
 */
fun Double.roundTo(decimals: Int): Double {
    if (this.isNaN() || this.isInfinite()) return this
    val multiplier = 10.0.pow(decimals)
    return (this * multiplier).roundToLong() / multiplier
}

/**
 * 复权DSL构建器
 */
class AdjustmentBuilder {
    private val events = mutableListOf<AdjustmentEvent>()

    /**
     * 添加分红事件
     */
    fun dividend(date: LocalDate, cashPerShare: Double, description: String = "分红派息") {
        events.add(AdjustmentEvent.Dividend(date, cashPerShare, description))
    }

    /**
     * 添加送股/转增事件
     */
    fun stockSplit(date: LocalDate, ratio: Double, description: String = "送股/转增") {
        events.add(AdjustmentEvent.StockSplit(date, ratio, description))
    }

    /**
     * 添加配股事件
     */
    fun rightsIssue(date: LocalDate, ratio: Double, price: Double, description: String = "配股") {
        events.add(AdjustmentEvent.RightsIssue(date, ratio, price, description))
    }

    /**
     * 添加综合除权除息事件
     */
    fun composite(
        date: LocalDate,
        dividend: Double = 0.0,
        stockSplitRatio: Double = 0.0,
        rightsIssueRatio: Double = 0.0,
        rightsIssuePrice: Double = 0.0,
        description: String = "除权除息"
    ) {
        events.add(AdjustmentEvent.Composite(
            date, dividend, stockSplitRatio, rightsIssueRatio, rightsIssuePrice, description
        ))
    }

    fun build(): List<AdjustmentEvent> = events.sortedBy { it.date }
}

/**
 * DSL入口函数
 */
inline fun adjustmentEvents(builder: AdjustmentBuilder.() -> Unit): List<AdjustmentEvent> {
    return AdjustmentBuilder().apply(builder).build()
}

/**
 * 扩展函数：对K线列表应用前复权
 */
inline fun <reified T : AdjustableCandle> List<T>.forwardAdjust(
    events: List<AdjustmentEvent>,
    latestClose: Double = this.lastOrNull()?.close ?: 0.0
): AdjustmentResult<T> {
    val factors = AdjustmentCalculator.calculateFactors(events, latestClose)
    val adjusted = AdjustmentCalculator.applyForwardAdjustment(this, factors)
    return AdjustmentResult(adjusted, factors, AdjustmentType.FORWARD)
}

/**
 * 扩展函数：对K线列表应用后复权
 */
inline fun <reified T : AdjustableCandle> List<T>.backwardAdjust(
    events: List<AdjustmentEvent>,
    latestClose: Double = this.lastOrNull()?.close ?: 0.0
): AdjustmentResult<T> {
    val factors = AdjustmentCalculator.calculateFactors(events, latestClose)
    val adjusted = AdjustmentCalculator.applyBackwardAdjustment(this, factors)
    return AdjustmentResult(adjusted, factors, AdjustmentType.BACKWARD)
}

/**
 * 扩展函数：批量复权计算
 */
inline fun <reified T : AdjustableCandle> List<T>.adjust(
    type: AdjustmentType,
    events: List<AdjustmentEvent>,
    latestClose: Double = this.lastOrNull()?.close ?: 0.0
): AdjustmentResult<T> = when (type) {
    AdjustmentType.FORWARD -> forwardAdjust(events, latestClose)
    AdjustmentType.BACKWARD -> backwardAdjust(events, latestClose)
}
