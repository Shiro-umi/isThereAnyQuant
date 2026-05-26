package org.shiroumi.strategy.core.intraday

import kotlinx.datetime.LocalDate
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.StockFactorSnapshot
import org.shiroumi.strategy.core.sentiment.SentimentMath
import utils.logger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val logger by logger("IntradaySentimentCalculator")

// 窗口大小常量
private const val FFT_WINDOW = 19
private const val RES_WINDOW = 228
private const val Z_WINDOW = 252
private const val ACCEL_EMA_SPAN = 10

// 权重常量（与盘后计算保持一致）
private const val W_FFT = 0.066
private const val W_RES = 0.241
private const val W_RATIO = 0.250
private const val W_VOL = 0.232
private const val W_ACCEL = 0.211

// 动态权重调整阈值（与盘后 dynamicRatioWeight 一致；
// 与 SentimentMath.VOL_CAP_THRESH 数值相同但语义不同：
// 此处控制 wRatio↔wVol 调权点，VOL_CAP_THRESH 控制 volCap 起作用的位置）
private const val VOL_DYN_THRESH = 2.0

// 默认参数
private const val DEFAULT_VOL_DYN_BOOST = 0.40
private const val MIN_SAMPLE_COVERAGE_RATIO = 0.9

/**
 * 滑动窗口数据结构 - 支持O(1)增量更新
 */
private class SlidingWindow(private val capacity: Int) {
    private val values = DoubleArray(capacity)
    private var nextIndex = 0
    private var size = 0
    private var sum = 0.0
    private var sumSquares = 0.0

    /**
     * 添加新值，如果窗口已满则替换最旧值
     */
    fun add(value: Double) {
        if (size == capacity) {
            val removed = values[nextIndex]
            sum -= removed
            sumSquares -= removed * removed
        } else {
            size += 1
        }
        values[nextIndex] = value
        nextIndex = (nextIndex + 1) % capacity
        sum += value
        sumSquares += value * value
    }

    /**
     * 从已有数据批量初始化
     */
    fun initialize(data: List<Double>) {
        data.takeLast(capacity).forEach { add(it) }
    }

    /**
     * 获取当前窗口大小
     */
    fun size(): Int = size

    /**
     * 获取窗口容量
     */
    fun capacity(): Int = capacity

    /**
     * 获取当前窗口数据（按时间顺序）
     */
    fun toArray(): DoubleArray {
        if (size == 0) return DoubleArray(0)
        val result = DoubleArray(size)
        for (i in 0 until size) {
            val idx = (nextIndex - size + i + capacity) % capacity
            result[i] = values[idx]
        }
        return result
    }

    /**
     * 获取最后一个值
     */
    fun last(): Double? = if (size > 0) {
        val idx = (nextIndex - 1 + capacity) % capacity
        values[idx]
    } else null

    /**
     * 计算标准差
     */
    fun standardDeviation(): Double {
        if (size < 2) return 0.0
        val mean = sum / size
        val variance = (sumSquares / size) - (mean * mean)
        return sqrt(max(variance, 0.0))
    }

    /**
     * 计算Z-Score
     */
    fun zScore(value: Double): Double {
        if (size < 2) return 0.0
        val mean = sum / size
        val std = standardDeviation()
        return if (std > 1e-8) (value - mean) / std else 0.0
    }
}

/**
 * 盘中市场情绪计算器
 *
 * 核心设计原则：
 * 1. 500只样本股循环是必要开销，其他计算必须O(1)
 * 2. 使用滑动窗口维护历史序列，避免全量重算
 * 3. 复用T-1盘后情绪作为基准，增量更新
 */
object IntradaySentimentCalculator {

    /**
     * 基于盘中因子增量计算市场情绪
     *
     * @param tradeDate 交易日期
     * @param baseSentiment T-1盘后情绪快照，作为计算基准
     * @param intradayFactors T日盘中因子快照列表
     * @param sampleCodes 500只情绪样本股代码列表
     * @return 更新后的市场情绪快照
     */
    fun calculateIncremental(
        tradeDate: LocalDate,
        baseSentiment: MarketSentimentSnapshot,
        intradayFactors: List<StockFactorSnapshot>,
        sampleCodes: List<String>,
        bullRatioHistorySeed: List<Double> = emptyList(),
        marketVolHistorySeed: List<Double> = emptyList(),
        accelHistorySeed: List<Double> = emptyList(),
    ): MarketSentimentSnapshot {
        val startTime = System.currentTimeMillis()

        // 1. 提取样本股因子 - O(500)
        val sampleFactorMap = intradayFactors
            .filter { it.tsCode in sampleCodes }
            .associateBy { it.tsCode }
        val sampleFactors = sampleCodes.mapNotNull { sampleFactorMap[it] }
        val expectedSampleSize = sampleCodes.size

        if (sampleFactors.isEmpty()) {
            logger.warning("[盘中情绪] 样本股因子为空，返回基准情绪")
            return baseSentiment.copy(
                tradeDate = tradeDate,
                reason = "样本股因子为空"
            )
        }
        val coverageRatio = if (expectedSampleSize > 0) {
            sampleFactors.size.toDouble() / expectedSampleSize.toDouble()
        } else {
            0.0
        }
        if (coverageRatio < MIN_SAMPLE_COVERAGE_RATIO) {
            logger.warning(
                "[盘中情绪] 样本覆盖不足，返回基准情绪 | " +
                    "available=${sampleFactors.size}, expected=$expectedSampleSize, " +
                    "coverage=${formatDouble(coverageRatio)}"
            )
            return baseSentiment.copy(
                tradeDate = tradeDate,
                reason = "样本覆盖不足(${sampleFactors.size}/$expectedSampleSize)"
            )
        }

        // 2. 计算实时bullRatio - O(500)，最轻量
        val bullishCount = sampleFactors.count { it.emaBull }
        val bullRatio = bullishCount.toDouble() / sampleFactors.size

        // 3. 从 baseSentiment 恢复历史序列并滑动更新
        val bullRatioHistory = restoreBullRatioHistory(
            baseSentiment = baseSentiment,
            historySeed = bullRatioHistorySeed
        )
        val previousBullRatio = bullRatioHistory.last()
        bullRatioHistory.add(bullRatio)
        val bullRatioSeries = bullRatioHistory.toArray()

        // 4. 更新 FFT / Residual
        val fftScore = calculateFftScore(bullRatioHistory)
        val residualScore = calculateResidualScore(bullRatioHistory)

        // 5. 盘中当前缺少与日频同语义的“20日收益率滚动波动率”实时输入。
        // 若把 intraday ATR/close 直接 append 到日频 marketVol 历史窗口，会让 volZ 发生跨量纲跳变。
        // 这里先严格锚定到 T-1 盘后已确认的波动维度，避免用错误量纲污染实时情绪。
        val marketVol = baseSentiment.marketVol
        val volZ = baseSentiment.volZ

        // 7. 计算动量加速 Z-Score（与日频一致）
        val accelZ = calculateAccelZ(
            bullRatioSeries = bullRatioSeries,
            previousBullRatio = previousBullRatio,
            currentBullRatio = bullRatio,
            accelHistorySeed = accelHistorySeed
        )

        // 8. 五维融合（与日频退化分支一致）
        val ratioNorm = SentimentMath.normalizeBullRatio(bullRatio)
        val volScore = SentimentMath.sigmoid(-volZ)
        val accelScore = SentimentMath.sigmoid(2.0 * accelZ)
        val fftValid = bullRatioHistory.size() >= FFT_WINDOW
        val resValid = bullRatioHistory.size() >= RES_WINDOW

        val exposure = when {
            fftValid && resValid -> {
                combineScores(
                    fftScore = fftScore,
                    residualScore = residualScore,
                    ratioNorm = ratioNorm,
                    volScore = volScore,
                    accelScore = accelScore,
                    volZ = volZ,
                    volDynBoost = DEFAULT_VOL_DYN_BOOST
                )
            }
            fftValid && !resValid -> {
                val (wVol, wRatio) = dynamicRatioWeight(volZ, DEFAULT_VOL_DYN_BOOST)
                val weights = normalizeWeights(
                    wFft = W_FFT,
                    wRes = 0.0,
                    wRatio = wRatio,
                    wVol = wVol,
                    wAccel = W_ACCEL
                )
                weights.nFft * fftScore +
                    weights.nRatio * ratioNorm +
                    weights.nVol * volScore +
                    weights.nAccel * accelScore
            }
            else -> ratioNorm
        }

        // 9. 应用保护机制
        val (finalExposure, absoluteFloorActive, volCap) = applyGuards(
            exposure = exposure,
            bullRatio = bullRatio,
            volZ = volZ
        )

        // 10. 计算派生指标

        val elapsed = System.currentTimeMillis() - startTime
        logger.info(
            "[盘中情绪] 计算完成 | tradeDate=$tradeDate, " +
                "bullRatio=${formatDouble(bullRatio)}, fftScore=${formatDouble(fftScore)}, " +
                "residualScore=${formatDouble(residualScore)}, volZ=${formatDouble(volZ)}, " +
                "accelZ=${formatDouble(accelZ)}, finalExposure=${formatDouble(finalExposure)}, " +
                "elapsed=${elapsed}ms"
        )

        return MarketSentimentSnapshot(
            tradeDate = tradeDate,
            signalBasis = baseSentiment.signalBasis,
            sampleSize = sampleFactors.size,
            bullRatio = bullRatio,
            fftScore = fftScore,
            residualScore = residualScore,
            marketVol = marketVol,
            volZ = volZ,
            accelZ = accelZ,
            sentimentExposure = finalExposure.coerceIn(0.0, 1.0),
            ratioNorm = ratioNorm,
            volScore = volScore,
            accelScore = accelScore,
            absoluteFloor = if (absoluteFloorActive) 0.0 else 1.0,
            volCap = volCap,
            sufficientHistory = baseSentiment.sufficientHistory,
            requiredHistory = baseSentiment.requiredHistory,
            reason = null
        )
    }

    /**
     * 从日频快照恢复 bullRatio 历史序列。
     * 取 tradeDate 及其之前最近 N 个日频快照，保持盘中窗口与盘后窗口同一语义。
     */
    private fun restoreBullRatioHistory(
        baseSentiment: MarketSentimentSnapshot,
        historySeed: List<Double> = emptyList()
    ): SlidingWindow {
        val window = SlidingWindow(max(RES_WINDOW, Z_WINDOW))
        val history = historySeed.ifEmpty { listOf(baseSentiment.bullRatio) }
        window.initialize(history)
        return window
    }

    /**
     * 计算FFT Score - O(19 log 19)
     */
    private fun calculateFftScore(bullRatioHistory: SlidingWindow): Double {
        if (bullRatioHistory.size() < FFT_WINDOW) {
            // 历史不足，使用中性值
            return 0.5
        }
        val window = bullRatioHistory.toArray().takeLast(FFT_WINDOW).toDoubleArray()
        return fftScore(window)
    }

    /**
     * 计算Residual Score - O(228)
     */
    private fun calculateResidualScore(bullRatioHistory: SlidingWindow): Double {
        if (bullRatioHistory.size() < RES_WINDOW) {
            return 0.5
        }
        val window = bullRatioHistory.toArray().takeLast(RES_WINDOW).toDoubleArray()
        return residualScore(window)
    }

    /**
     * 计算动量加速 Z-Score（与日频一致）
     */
    private fun calculateAccelZ(
        bullRatioSeries: DoubleArray,
        previousBullRatio: Double?,
        currentBullRatio: Double,
        accelHistorySeed: List<Double>
    ): Double {
        if (accelHistorySeed.isNotEmpty()) {
            val accelWindow = SlidingWindow(Z_WINDOW)
            accelWindow.initialize(accelHistorySeed)
            val diff = previousBullRatio?.let { currentBullRatio - it } ?: 0.0
            val previousAccel = accelWindow.last()
            accelWindow.add(updateEma(previousAccel, diff, ACCEL_EMA_SPAN))
            val values = accelWindow.toArray()
            return latestZScore(values, minPeriods = 60)
        }
        val accelSeries = emaSeries(differenceSeries(bullRatioSeries), ACCEL_EMA_SPAN)
        return zScoreSeries(accelSeries, Z_WINDOW, 60).lastOrNull() ?: 0.0
    }

    /**
     * 五维融合（与日频一致）
     */
    private fun combineScores(
        fftScore: Double,
        residualScore: Double,
        ratioNorm: Double,
        volScore: Double,
        accelScore: Double,
        volZ: Double,
        volDynBoost: Double = DEFAULT_VOL_DYN_BOOST
    ): Double {
        val (wVol, wRatio) = dynamicRatioWeight(volZ, volDynBoost)
        val weights = normalizeWeights(
            wFft = W_FFT,
            wRes = W_RES,
            wRatio = wRatio,
            wVol = wVol,
            wAccel = W_ACCEL
        )

        return weights.nFft * fftScore +
            weights.nRes * residualScore +
            weights.nRatio * ratioNorm +
            weights.nVol * volScore +
            weights.nAccel * accelScore
    }

    /**
     * 应用保护机制
     * @return Triple(最终仓位, 是否触发绝对水位保护, volCap值)
     */
    private fun applyGuards(
        exposure: Double,
        bullRatio: Double,
        volZ: Double
    ): Triple<Double, Boolean, Double> {
        // 绝对水位保护：bullRatio < 0.256 则空仓
        val absoluteFloorActive = bullRatio < SentimentMath.ABSOLUTE_FLOOR
        var finalExposure = if (absoluteFloorActive) 0.0 else exposure

        // 波动率上限保护：volZ > 2.0 时动态降权
        val volCap = SentimentMath.calculateVolCap(volZ)
        finalExposure = min(finalExposure, volCap)

        return Triple(finalExposure, absoluteFloorActive, volCap)
    }

    /**
     * 动态权重调整
     */
    private fun dynamicRatioWeight(volZ: Double, volDynBoost: Double): Pair<Double, Double> {
        val excess = max(0.0, volZ - VOL_DYN_THRESH)
        val wVolBoost = (volDynBoost * excess).coerceAtMost(W_RATIO * 0.8)
        val wVol = W_VOL + wVolBoost
        val wRatio = max(W_RATIO - wVolBoost, W_RATIO * 0.2)
        return wVol to wRatio
    }

    /**
     * 权重归一化
     */
    private data class NormalizedWeights(
        val nFft: Double,
        val nRes: Double,
        val nRatio: Double,
        val nVol: Double,
        val nAccel: Double
    )

    private fun normalizeWeights(
        wFft: Double,
        wRes: Double,
        wRatio: Double,
        wVol: Double,
        wAccel: Double
    ): NormalizedWeights {
        val total = wFft + wRes + wRatio + wVol + wAccel
        val denom = total.coerceAtLeast(1e-12)
        return NormalizedWeights(
            nFft = wFft / denom,
            nRes = wRes / denom,
            nRatio = wRatio / denom,
            nVol = wVol / denom,
            nAccel = wAccel / denom
        )
    }

    /**
     * 增量 EMA 更新
     */
    private fun updateEma(prevEma: Double?, value: Double, period: Int): Double {
        val multiplier = 2.0 / (period + 1)
        return prevEma?.let { (value - it) * multiplier + it } ?: value
    }

    private fun differenceSeries(values: DoubleArray): DoubleArray {
        val result = DoubleArray(values.size)
        for (index in values.indices) {
            result[index] = if (index == 0) 0.0 else values[index] - values[index - 1]
        }
        return result
    }

    private fun emaSeries(values: DoubleArray, period: Int): DoubleArray {
        val result = DoubleArray(values.size)
        var prev: Double? = null
        for (index in values.indices) {
            val ema = updateEma(prev, values[index], period)
            result[index] = ema
            prev = ema
        }
        return result
    }

    private fun zScoreSeries(values: DoubleArray, window: Int, minPeriods: Int): DoubleArray {
        val out = DoubleArray(values.size)
        for (index in values.indices) {
            val from = max(0, index - window + 1)
            val size = index - from + 1
            if (size < minPeriods) {
                out[index] = 0.0
                continue
            }
            var sum = 0.0
            for (i in from..index) sum += values[i]
            val mean = sum / size
            var variance = 0.0
            for (i in from..index) {
                val diff = values[i] - mean
                variance += diff * diff
            }
            val std = sqrt(variance / size).takeIf { it > 1e-8 } ?: 1.0
            out[index] = (values[index] - mean) / std
        }
        return out
    }

    /**
     * 直接计算当前序列最后一个点的 z-score。
     *
     * 这个版本专门服务于“seed 已经给出完整滚动窗口，只需要追加一个新值”的场景，
     * 避免再次从头生成整条 z-score 序列。
     */
    private fun latestZScore(values: DoubleArray, minPeriods: Int): Double {
        if (values.size < minPeriods) return 0.0
        val mean = values.average()
        var variance = 0.0
        values.forEach { value ->
            val diff = value - mean
            variance += diff * diff
        }
        val std = sqrt(variance / values.size)
        return if (std > 1e-8) {
            (values.last() - mean) / std
        } else {
            0.0
        }
    }

    /**
     * FFT Score计算 - 频域分析
     */
    private fun fftScore(values: DoubleArray): Double {
        if (standardDeviation(values) < 1e-6) return 0.5
        val mean = values.average()
        val centered = DoubleArray(values.size) { values[it] - mean }
        val coeffs = (1..(centered.size / 2)).map { k ->
            var re = 0.0
            var im = 0.0
            for (n in centered.indices) {
                re += centered[n] * cos(2.0 * PI * k * n / centered.size)
                im += -centered[n] * sin(2.0 * PI * k * n / centered.size)
            }
            Triple(k, re, im)
        }
        val top = coeffs.sortedByDescending { (_, re, im) -> re * re + im * im }.take(2)
        val totalMag = top.sumOf { (_, re, im) -> sqrt(re * re + im * im) } + 1e-8
        val score = top.sumOf { (_, re, im) ->
            val magnitude = sqrt(re * re + im * im)
            val phase = kotlin.math.atan2(im, re)
            (magnitude / totalMag) * (-sin(phase))
        }
        return SentimentMath.sigmoid(3.0 * score)
    }

    /**
     * Residual Score计算 - Ridge回归残差分析
     */
    private fun residualScore(values: DoubleArray): Double {
        if (standardDeviation(values) < 1e-6) return 0.5
        val n = values.size
        val t = DoubleArray(n) { it.toDouble() }
        val cols = mutableListOf<DoubleArray>()
        cols += DoubleArray(n) { 1.0 }
        for (k in 1..3) {
            val freq = 2.0 * PI * k / n.toDouble()
            cols += DoubleArray(n) { i -> sin(freq * t[i]) }
            cols += DoubleArray(n) { i -> cos(freq * t[i]) }
        }
        val beta = ridgeSolve(cols, values, alpha = 0.1)
        val fitted = DoubleArray(n) { i -> cols.indices.sumOf { colIndex -> cols[colIndex][i] * beta[colIndex] } }
        val residual = values.last() - fitted.last()
        val residualSeries = DoubleArray(n) { values[it] - fitted[it] }
        val std = standardDeviation(residualSeries).takeIf { it > 1e-8 } ?: 1.0
        return SentimentMath.sigmoid(-4.0 * (residual / std))
    }

    /**
     * Ridge回归求解
     */
    private fun ridgeSolve(columns: List<DoubleArray>, y: DoubleArray, alpha: Double): DoubleArray {
        val p = columns.size
        val a = Array(p) { DoubleArray(p) }
        val b = DoubleArray(p)
        for (i in 0 until p) {
            for (j in 0 until p) {
                a[i][j] = dot(columns[i], columns[j]) + if (i == j) alpha else 0.0
            }
            b[i] = dot(columns[i], y)
        }
        return gaussianElimination(a, b)
    }

    /**
     * 高斯消元法
     */
    private fun gaussianElimination(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        for (i in 0 until n) {
            var maxRow = i
            for (k in i + 1 until n) {
                if (abs(a[k][i]) > abs(a[maxRow][i])) maxRow = k
            }
            a[i] = a[maxRow].also { a[maxRow] = a[i] }
            val tmpB = b[i]
            b[i] = b[maxRow]
            b[maxRow] = tmpB

            val pivot = a[i][i].takeIf { abs(it) > 1e-12 } ?: 1e-12
            for (j in i until n) a[i][j] /= pivot
            b[i] /= pivot

            for (k in 0 until n) {
                if (k == i) continue
                val factor = a[k][i]
                for (j in i until n) a[k][j] -= factor * a[i][j]
                b[k] -= factor * b[i]
            }
        }
        return b
    }

    private fun dot(x: DoubleArray, y: DoubleArray): Double {
        var sum = 0.0
        for (i in x.indices) sum += x[i] * y[i]
        return sum
    }

    private fun standardDeviation(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        var variance = 0.0
        for (value in values) {
            val diff = value - mean
            variance += diff * diff
        }
        return sqrt(variance / values.size)
    }

    /**
     * 格式化double，避免使用String.format（JS平台不兼容）
     */
    private fun formatDouble(value: Double): String {
        val rounded = kotlin.math.round(value * 10000) / 10000
        return rounded.toString()
    }
}
