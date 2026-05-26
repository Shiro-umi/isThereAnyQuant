package org.shiroumi.quant_kmp.algorithm.adjustment

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * 复权配置
 */
data class AdjustmentServiceConfig(
    val defaultAdjustmentType: AdjustmentType = AdjustmentType.FORWARD,
    val includeVolumeAdjustment: Boolean = true,
    val precision: Int = 6
)

/**
 * 复权服务
 * 提供高级复权功能和便捷API
 */
class AdjustmentService(
    val config: AdjustmentServiceConfig = AdjustmentServiceConfig()
) {

    /**
     * 批量复权处理
     * 支持多只股票同时处理
     *
     * @param dataMap 股票代码到K线数据的映射
     * @param eventsMap 股票代码到除权除息事件的映射
     * @param type 复权类型
     * @return 股票代码到复权结果的映射
     */
    inline fun <reified T : AdjustableCandle> batchAdjust(
        dataMap: Map<String, List<T>>,
        eventsMap: Map<String, List<AdjustmentEvent>>,
        type: AdjustmentType = config.defaultAdjustmentType
    ): Map<String, AdjustmentResult<T>> {
        return dataMap.mapValues { (symbol, candles) ->
            val events = eventsMap[symbol] ?: emptyList()
            candles.adjust(type, events)
        }
    }

    /**
     * 计算复权后的收益率
     *
     * @param candles 复权后的K线数据
     * @param startIndex 起始索引
     * @param endIndex 结束索引
     * @return 收益率（小数形式，如0.1表示10%）
     */
    fun calculateReturn(
        candles: List<AdjustableCandle>,
        startIndex: Int = 0,
        endIndex: Int = candles.size - 1
    ): Double {
        if (candles.isEmpty() || startIndex < 0 || endIndex >= candles.size || startIndex >= endIndex) {
            return 0.0
        }

        val startPrice = candles[startIndex].close
        val endPrice = candles[endIndex].close

        return if (startPrice > 0) {
            (endPrice - startPrice) / startPrice
        } else {
            0.0
        }
    }

    /**
     * 计算复权后的累计收益率序列
     *
     * @param candles 复权后的K线数据
     * @return 累计收益率序列（与K线数据一一对应）
     */
    fun calculateCumulativeReturns(
        candles: List<AdjustableCandle>
    ): List<Double> {
        if (candles.isEmpty()) return emptyList()

        val basePrice = candles.first().close
        if (basePrice <= 0) return List(candles.size) { 0.0 }

        return candles.map { candle ->
            (candle.close - basePrice) / basePrice
        }
    }

    /**
     * 计算复权后的移动平均收益率
     *
     * @param candles 复权后的K线数据
     * @param period 计算周期
     * @return 移动平均收益率序列
     */
    fun calculateMovingAverageReturns(
        candles: List<AdjustableCandle>,
        period: Int = 20
    ): List<Double?> {
        if (candles.size < period) return List(candles.size) { null }

        val returns = calculateCumulativeReturns(candles)
        return returns.mapIndexed { index, _ ->
            if (index < period - 1) {
                null
            } else {
                val window = returns.subList(index - period + 1, index + 1)
                window.average()
            }
        }
    }

    /**
     * 查找除权除息日
     *
     * @param candles K线数据
     * @param factors 复权因子列表
     * @return 除权除息日期列表
     */
    fun findExDivDates(
        candles: List<AdjustableCandle>,
        factors: List<AdjustmentFactor>
    ): List<LocalDate> {
        return factors.map { it.date }
    }

    /**
     * 验证复权结果的一致性
     * 检查复权后的价格关系是否正确
     *
     * @param candles 复权后的K线数据
     * @return 验证结果
     */
    fun validateAdjustment(candles: List<AdjustableCandle>): ValidationResult {
        val errors = mutableListOf<String>()

        candles.forEachIndexed { index, candle ->
            // 检查价格关系
            if (candle.high < candle.open || candle.high < candle.close) {
                errors.add("Index $index: high(${candle.high}) < open(${candle.open}) or close(${candle.close})")
            }
            if (candle.low > candle.open || candle.low > candle.close) {
                errors.add("Index $index: low(${candle.low}) > open(${candle.open}) or close(${candle.close})")
            }
            if (candle.high < candle.low) {
                errors.add("Index $index: high(${candle.high}) < low(${candle.low})")
            }

            // 检查非负值
            if (candle.volume < 0) {
                errors.add("Index $index: volume is negative")
            }
            if (candle.amount < 0) {
                errors.add("Index $index: amount is negative")
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            totalCandles = candles.size
        )
    }

    /**
     * 获取复权前后的价格对比
     *
     * @param original 原始K线数据
     * @param adjusted 复权后的K线数据
     * @return 价格对比列表
     */
    fun getPriceComparison(
        original: List<AdjustableCandle>,
        adjusted: List<AdjustableCandle>
    ): List<PriceComparison> {
        if (original.size != adjusted.size) {
            throw IllegalArgumentException("Original and adjusted lists must have the same size")
        }

        return original.zip(adjusted) { orig, adj ->
            PriceComparison(
                date = orig.date,
                originalClose = orig.close,
                adjustedClose = adj.close,
                changeRatio = if (orig.close != 0.0) (adj.close - orig.close) / orig.close else 0.0,
                originalVolume = orig.volume,
                adjustedVolume = adj.volume
            )
        }
    }

    /**
     * 计算复权因子序列（用于图表展示）
     *
     * @param candles K线数据
     * @param factors 复权因子列表
     * @param type 复权类型
     * @return 与K线数据对应的复权因子序列
     */
    fun calculateFactorSeries(
        candles: List<AdjustableCandle>,
        factors: List<AdjustmentFactor>,
        type: AdjustmentType
    ): List<Double> {
        if (factors.isEmpty()) return List(candles.size) { 1.0 }

        return when (type) {
            AdjustmentType.FORWARD -> {
                val latestFactor = factors.last().forwardFactor
                candles.map { candle ->
                    val applicableFactors = factors.filter { it.date <= candle.date }
                    if (applicableFactors.isEmpty()) {
                        latestFactor
                    } else {
                        latestFactor / applicableFactors.last().forwardFactor
                    }
                }
            }
            AdjustmentType.BACKWARD -> {
                candles.map { candle ->
                    val applicableFactors = factors.filter { it.date < candle.date }
                    if (applicableFactors.isEmpty()) {
                        1.0
                    } else {
                        applicableFactors.last().backwardFactor
                    }
                }
            }
        }
    }

    /**
     * 创建前复权K线（从后复权转换）
     *
     * 原理：前复权价格 = 后复权价格 × (该日期前复权因子 / 该日期后复权因子)
     * 其中前复权因子 = 该日期之后所有事件的 forwardFactor 的乘积
     * 后复权因子 = 该日期及之前所有事件的 backwardFactor 的乘积
     *
     * @param backwardAdjusted 后复权K线数据
     * @param factors 复权因子列表
     * @return 前复权K线数据
     */
    fun <T : AdjustableCandle> convertToForward(
        backwardAdjusted: List<T>,
        factors: List<AdjustmentFactor>
    ): List<T> {
        if (factors.isEmpty() || backwardAdjusted.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            return backwardAdjusted as List<T>
        }

        return backwardAdjusted.map { candle ->
            // 计算该日期的前复权因子（该日期之后所有事件的 forwardFactor 的乘积）
            val futureFactors = factors.filter { it.date > candle.date }
            val candleForwardFactor = if (futureFactors.isEmpty()) {
                1.0  // 没有未来事件，前复权因子为1（价格不变）
            } else {
                futureFactors.map { it.forwardFactor }.reduce { acc, f -> acc * f }
            }

            // 计算该日期的后复权因子（该日期及之前所有事件的 backwardFactor 的乘积）
            val pastFactors = factors.filter { it.date <= candle.date }
            val candleBackwardFactor = if (pastFactors.isEmpty()) {
                1.0
            } else {
                pastFactors.map { it.backwardFactor }.reduce { acc, f -> acc * f }
            }

            // 转换因子 = 前复权因子 / 后复权因子
            val conversionFactor = candleForwardFactor / candleBackwardFactor

            @Suppress("UNCHECKED_CAST")
            candle.withAdjustedPrices(
                open = candle.open * conversionFactor,
                high = candle.high * conversionFactor,
                low = candle.low * conversionFactor,
                close = candle.close * conversionFactor,
                volume = candle.volume / conversionFactor,
                amount = candle.amount
            ) as T
        }
    }

    /**
     * 创建后复权K线（从前复权转换）
     *
     * 原理：后复权价格 = 前复权价格 × (该日期后复权因子 / 该日期前复权因子)
     * 其中前复权因子 = 该日期之后所有事件的 forwardFactor 的乘积
     * 后复权因子 = 该日期及之前所有事件的 backwardFactor 的乘积
     *
     * @param forwardAdjusted 前复权K线数据
     * @param factors 复权因子列表
     * @return 后复权K线数据
     */
    fun <T : AdjustableCandle> convertToBackward(
        forwardAdjusted: List<T>,
        factors: List<AdjustmentFactor>
    ): List<T> {
        if (factors.isEmpty() || forwardAdjusted.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            return forwardAdjusted as List<T>
        }

        return forwardAdjusted.map { candle ->
            // 计算该日期的前复权因子（该日期之后所有事件的 forwardFactor 的乘积）
            val futureFactors = factors.filter { it.date > candle.date }
            val candleForwardFactor = if (futureFactors.isEmpty()) {
                1.0  // 没有未来事件，前复权因子为1（价格不变）
            } else {
                futureFactors.map { it.forwardFactor }.reduce { acc, f -> acc * f }
            }

            // 计算该日期的后复权因子（该日期及之前所有事件的 backwardFactor 的乘积）
            val pastFactors = factors.filter { it.date <= candle.date }
            val candleBackwardFactor = if (pastFactors.isEmpty()) {
                1.0
            } else {
                pastFactors.map { it.backwardFactor }.reduce { acc, f -> acc * f }
            }

            // 转换因子 = 后复权因子 / 前复权因子
            val conversionFactor = candleBackwardFactor / candleForwardFactor

            @Suppress("UNCHECKED_CAST")
            candle.withAdjustedPrices(
                open = candle.open * conversionFactor,
                high = candle.high * conversionFactor,
                low = candle.low * conversionFactor,
                close = candle.close * conversionFactor,
                volume = candle.volume / conversionFactor,
                amount = candle.amount
            ) as T
        }
    }
}

/**
 * 验证结果
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val totalCandles: Int
) {
    val errorCount: Int get() = errors.size
    val errorRate: Double get() = if (totalCandles > 0) errorCount.toDouble() / totalCandles else 0.0
}

/**
 * 价格对比数据
 */
@Serializable
data class PriceComparison(
    val date: LocalDate,
    val originalClose: Double,
    val adjustedClose: Double,
    val changeRatio: Double,
    val originalVolume: Double,
    val adjustedVolume: Double
)

/**
 * 复权结果分析器
 * 提供复权结果的深度分析功能
 */
object AdjustmentAnalyzer {

    /**
     * 分析复权影响
     *
     * @param comparison 价格对比数据
     * @return 复权影响分析
     */
    fun analyzeImpact(comparison: List<PriceComparison>): ImpactAnalysis {
        if (comparison.isEmpty()) {
            return ImpactAnalysis(0.0, 0.0, 0.0, emptyMap())
        }

        val ratios = comparison.map { it.changeRatio }
        val avgImpact = ratios.average()
        val maxImpact = ratios.maxOrNull() ?: 0.0
        val minImpact = ratios.minOrNull() ?: 0.0

        // 按年份分组统计
        val yearlyImpact = comparison.groupBy {
            it.date.year
        }.mapValues { (_, items) ->
            items.map { it.changeRatio }.average()
        }

        return ImpactAnalysis(
            averageImpact = avgImpact,
            maxImpact = maxImpact,
            minImpact = minImpact,
            yearlyImpact = yearlyImpact
        )
    }

    /**
     * 检测异常复权
     * 识别可能的除权除息数据错误
     *
     * @param candles 复权后的K线数据
     * @param threshold 异常阈值（价格跳变比例）
     * @return 异常日期列表
     */
    fun detectAnomalies(
        candles: List<AdjustableCandle>,
        threshold: Double = 0.2
    ): List<AnomalyDetection> {
        if (candles.size < 2) return emptyList()

        val anomalies = mutableListOf<AnomalyDetection>()

        for (i in 1 until candles.size) {
            val prev = candles[i - 1]
            val curr = candles[i]

            // 检测价格跳变
            val priceChange = if (prev.close != 0.0) {
                kotlin.math.abs(curr.close - prev.close) / prev.close
            } else {
                if (curr.close != 0.0) Double.POSITIVE_INFINITY else 0.0
            }

            // 检测成交量异常
            val volumeChange = when {
                prev.volume == 0.0 && curr.volume > 0 -> Double.POSITIVE_INFINITY
                prev.volume == 0.0 && curr.volume == 0.0 -> 0.0
                prev.volume != 0.0 -> kotlin.math.abs(curr.volume - prev.volume) / prev.volume
                else -> 0.0
            }

            if (priceChange > threshold || volumeChange > threshold * 2) {
                anomalies.add(
                    AnomalyDetection(
                        date = curr.date,
                        priceChangeRatio = priceChange,
                        volumeChangeRatio = volumeChange,
                        isPriceAnomaly = priceChange > threshold,
                        isVolumeAnomaly = volumeChange > threshold * 2
                    )
                )
            }
        }

        return anomalies
    }

    /**
     * 生成复权报告
     *
     * @param result 复权结果
     * @param original 原始数据
     * @return 复权报告
     */
    fun <T : AdjustableCandle> generateReport(
        result: AdjustmentResult<T>,
        original: List<T>
    ): AdjustmentReport {
        val comparison = original.zip(result.candles) { orig, adj ->
            PriceComparison(
                date = orig.date,
                originalClose = orig.close,
                adjustedClose = adj.close,
                changeRatio = if (orig.close != 0.0) (adj.close - orig.close) / orig.close else 0.0,
                originalVolume = orig.volume,
                adjustedVolume = adj.volume
            )
        }

        val impact = analyzeImpact(comparison)
        val anomalies = detectAnomalies(result.candles)

        return AdjustmentReport(
            adjustmentType = result.type,
            totalCandles = result.candles.size,
            adjustmentEvents = result.factors.size,
            impactAnalysis = impact,
            anomalies = anomalies,
            priceComparisons = comparison
        )
    }
}

/**
 * 复权影响分析
 */
@Serializable
data class ImpactAnalysis(
    val averageImpact: Double,
    val maxImpact: Double,
    val minImpact: Double,
    val yearlyImpact: Map<Int, Double>
)

/**
 * 异常检测
 */
@Serializable
data class AnomalyDetection(
    val date: LocalDate,
    val priceChangeRatio: Double,
    val volumeChangeRatio: Double,
    val isPriceAnomaly: Boolean,
    val isVolumeAnomaly: Boolean
)

/**
 * 复权报告
 */
@Serializable
data class AdjustmentReport(
    val adjustmentType: AdjustmentType,
    val totalCandles: Int,
    val adjustmentEvents: Int,
    val impactAnalysis: ImpactAnalysis,
    val anomalies: List<AnomalyDetection>,
    val priceComparisons: List<PriceComparison>
) {
    val hasAnomalies: Boolean get() = anomalies.isNotEmpty()
    val anomalyCount: Int get() = anomalies.size
}
