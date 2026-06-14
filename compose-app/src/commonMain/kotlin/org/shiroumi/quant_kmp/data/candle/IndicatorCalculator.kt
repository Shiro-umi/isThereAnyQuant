package org.shiroumi.quant_kmp.data.candle

import kotlin.math.sqrt
import kotlin.math.abs

/**
 * 技术指标计算器
 * 提供各类技术分析指标的计算方法
 *
 * 性能约束：所有方法都跑在 wasmJs 主线程上。500 根 K 线 × 11 个指标会反复扫窗口，
 * 因此 SMA / Bollinger 都改成滚动累加，避免每点重复 subList + average。
 */
object IndicatorCalculator {

    /**
     * 计算简单移动平均列表
     * @param data 价格数据列表
     * @param period 计算周期
     * @return SMA值列表，前period-1个为null
     *
     * 实现：滚动求和。每次新点入窗 + 旧点出窗，O(N) 复杂度。
     */
    fun calculateSMAList(data: List<Double>, period: Int): List<Float?> {
        if (period <= 0 || data.size < period) return List(data.size) { null }
        val result = arrayOfNulls<Float?>(data.size)
        var windowSum = 0.0
        val inv = 1.0 / period
        for (i in data.indices) {
            windowSum += data[i]
            if (i >= period) windowSum -= data[i - period]
            if (i >= period - 1) result[i] = (windowSum * inv).toFloat()
        }
        return result.asList()
    }

    /**
     * 计算指数移动平均列表
     * @param data 价格数据列表
     * @param period 计算周期
     * @return EMA值列表，前period-1个为null
     *
     * 实现：第一个 EMA 用前 period 项做 SMA 起点，之后递推。
     * 起点 SMA 通过累加获得，避免重复触发 `subList(0, period).average()`。
     */
    fun calculateEMAList(data: List<Double>, period: Int): List<Float?> {
        if (period <= 0 || data.size < period) return List(data.size) { null }
        val result = arrayOfNulls<Float?>(data.size)
        val multiplier = 2.0 / (period + 1)
        var initialSum = 0.0
        for (i in 0 until period) initialSum += data[i]
        var ema = initialSum / period
        result[period - 1] = ema.toFloat()
        for (i in period until data.size) {
            ema = (data[i] - ema) * multiplier + ema
            result[i] = ema.toFloat()
        }
        return result.asList()
    }

    /**
     * 计算RSI列表（相对强弱指标）
     * @param data 价格数据列表
     * @param period 计算周期
     * @return RSI值列表，前period个为null
     */
    fun calculateRSIList(data: List<Double>, period: Int): List<Float?> {
        if (data.size < period + 1) return List(data.size) { null }

        val result = mutableListOf<Float?>()

        // 前period个数据没有RSI值
        repeat(period) {
            result.add(null)
        }

        // 计算初始平均涨跌
        var gains = 0.0
        var losses = 0.0

        for (i in 1..period) {
            val change = data[i] - data[i - 1]
            if (change > 0) gains += change else losses += abs(change)
        }

        var avgGain = gains / period
        var avgLoss = losses / period

        // 第一个RSI值
        val firstRsi = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
        result.add(firstRsi.toFloat())

        // 计算后续RSI值（使用平滑公式）
        for (i in period + 1 until data.size) {
            val change = data[i] - data[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period

            val rsi = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
            result.add(rsi.toFloat())
        }

        return result
    }

    /**
     * 计算MACD列表（指数平滑异同移动平均线）
     * @param data 价格数据列表
     * @param fastPeriod 快线周期，默认12
     * @param slowPeriod 慢线周期，默认26
     * @param signalPeriod 信号线周期，默认9
     * @return Triple(DIF列表, DEA列表, BAR列表)
     */
    fun calculateMACDList(
        data: List<Double>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): Triple<List<Float?>, List<Float?>, List<Float?>> {
        val minDataSize = slowPeriod + signalPeriod
        if (data.size < minDataSize) {
            return Triple(
                List(data.size) { null },
                List(data.size) { null },
                List(data.size) { null }
            )
        }

        val difList = mutableListOf<Float?>()
        val deaList = mutableListOf<Float?>()
        val barList = mutableListOf<Float?>()

        // 计算DIF序列 (EMA12 - EMA26)
        val ema12List = calculateEMAList(data, fastPeriod)
        val ema26List = calculateEMAList(data, slowPeriod)

        val difValues = data.indices.map { index ->
            val ema12 = ema12List.getOrNull(index)
            val ema26 = ema26List.getOrNull(index)
            if (ema12 != null && ema26 != null) ema12 - ema26 else null
        }

        // 计算DEA序列 (DIF的EMA9)
        val validDifValues = difValues.filterNotNull().map { it.toDouble() }
        val deaValues = if (validDifValues.size >= signalPeriod) {
            calculateEMAList(validDifValues, signalPeriod)
        } else emptyList()

        // 合并结果
        var deaIndex = 0
        data.forEachIndexed { index, _ ->
            val dif = difValues[index]
            difList.add(dif?.toFloat())

            if (dif != null && deaIndex < deaValues.size) {
                val dea = deaValues[deaIndex]
                deaList.add(dea)
                barList.add(if (dea != null) ((dif - dea) * 2).toFloat() else null)
                deaIndex++
            } else {
                deaList.add(null)
                barList.add(null)
            }
        }

        return Triple(difList, deaList, barList)
    }

    /**
     * 计算布林带列表（Bollinger Bands）
     * @param data 价格数据列表
     * @param period 计算周期，默认20
     * @param multiplier 标准差倍数，默认2.0
     * @return Triple(上轨列表, 中轨列表, 下轨列表)
     *
     * 实现：滚动求和 + 滚动平方和。
     * 方差 = E[x^2] - E[x]^2，可以靠两个累加器在 O(1) 内推进。
     * 这条改造把 Bollinger 从 500 × 20 次 pow 降到 500 次乘法。
     */
    fun calculateBollingerBandsList(
        data: List<Double>,
        period: Int = 20,
        multiplier: Double = 2.0
    ): Triple<List<Float?>, List<Float?>, List<Float?>> {
        if (period <= 0 || data.size < period) {
            val empty = List<Float?>(data.size) { null }
            return Triple(empty, empty, empty)
        }
        val upper = arrayOfNulls<Float?>(data.size)
        val mid = arrayOfNulls<Float?>(data.size)
        val lower = arrayOfNulls<Float?>(data.size)
        val inv = 1.0 / period
        var sum = 0.0
        var sumSq = 0.0
        for (i in data.indices) {
            val x = data[i]
            sum += x
            sumSq += x * x
            if (i >= period) {
                val out = data[i - period]
                sum -= out
                sumSq -= out * out
            }
            if (i >= period - 1) {
                val mean = sum * inv
                // 浮点累加误差可能让 variance 略小于 0，做一次 clamp 防 NaN。
                val variance = (sumSq * inv - mean * mean).coerceAtLeast(0.0)
                val stdDev = sqrt(variance)
                mid[i] = mean.toFloat()
                upper[i] = (mean + multiplier * stdDev).toFloat()
                lower[i] = (mean - multiplier * stdDev).toFloat()
            }
        }
        return Triple(upper.asList(), mid.asList(), lower.asList())
    }
}
