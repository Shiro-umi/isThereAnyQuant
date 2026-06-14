package org.shiroumi.strategy.core.daily

import kotlinx.datetime.LocalDate
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedBar
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

private val logger by logger("MarketSentimentCalculator")

private const val FFT_WINDOW = 19
private const val RES_WINDOW = 228
private const val VOL_WINDOW = 20
private const val Z_WINDOW = 252
private const val ACCEL_EMA_SPAN = 10
private const val SENT_EMA_S = 10
private const val SENT_EMA_L = 30

private const val W_FFT = 0.066
private const val W_RES = 0.241
private const val W_RATIO = 0.250
private const val W_VOL = 0.232
private const val W_ACCEL = 0.211
private const val VOL_DYN_THRESH = 2.0

private const val DEFAULT_VOL_DYN_BOOST = 0.40
private const val DEFAULT_SENT_OVERHEAT_THRESH = 0.0
private const val DEFAULT_SENT_OVERHEAT_DAYS = 10
private const val DEFAULT_SENT_OVERHEAT_DECAY = 1.0

data class MarketSentimentSnapshot(
    val tradeDate: LocalDate,
    val signalBasis: String,
    val sampleSize: Int,
    val bullRatio: Double,
    val fftScore: Double,
    val residualScore: Double,
    val marketVol: Double,
    val volZ: Double,
    val accelZ: Double,
    val sentimentExposure: Double,
    val ratioNorm: Double,
    val volScore: Double,
    val accelScore: Double,
    val absoluteFloor: Double,
    val volCap: Double,
    val sufficientHistory: Boolean,
    val requiredHistory: Int,
    val reason: String? = null,
)

private fun SymbolSentimentState.step(close: Double): SymbolSentimentState {
    val newEmaShort = updateEma(this.emaShort, close, SENT_EMA_S)
    val newEmaLong = updateEma(this.emaLong, close, SENT_EMA_L)
    val ret = if (prevClose != 0.0) (close - prevClose) / prevClose else 0.0

    val idx = nextReturnIndex
    val newReturns = recentReturns.toMutableList().apply { this[idx] = ret }

    val newSum: Double
    val newSumSq: Double
    val newWindowSize: Int
    if (returnWindowSize == recentReturns.size) {
        val removed = recentReturns[idx]
        newSum = returnSum - removed + ret
        newSumSq = returnSumSq - removed * removed + ret * ret
        newWindowSize = returnWindowSize
    } else {
        newSum = returnSum + ret
        newSumSq = returnSumSq + ret * ret
        newWindowSize = returnWindowSize + 1
    }

    return copy(
        emaShort = newEmaShort,
        emaLong = newEmaLong,
        prevClose = close,
        recentReturns = newReturns,
        nextReturnIndex = (idx + 1) % recentReturns.size,
        returnWindowSize = newWindowSize,
        returnSum = newSum,
        returnSumSq = newSumSq,
    )
}

private fun updateEma(previous: Double?, value: Double, period: Int): Double {
    val multiplier = 2.0 / (period + 1)
    return previous?.let { (value - it) * multiplier + it } ?: value
}

private data class NormalizedWeights(
    val nFft: Double,
    val nRes: Double,
    val nRatio: Double,
    val nVol: Double,
    val nAccel: Double,
)

private fun dynamicRatioWeight(volZ: Double, volDynBoost: Double): Pair<Double, Double> {
    val excess = max(0.0, volZ - VOL_DYN_THRESH)
    val wVolBoost = (volDynBoost * excess).coerceAtMost(W_RATIO * 0.8)
    val wVol = W_VOL + wVolBoost
    val wRatio = max(W_RATIO - wVolBoost, W_RATIO * 0.2)
    return wVol to wRatio
}

private fun normalizeWeights(
    wFft: Double,
    wRes: Double,
    wRatio: Double,
    wVol: Double,
    wAccel: Double,
): NormalizedWeights {
    val total = wFft + wRes + wRatio + wVol + wAccel
    val denom = total.coerceAtLeast(1e-12)
    return NormalizedWeights(
        nFft = wFft / denom,
        nRes = wRes / denom,
        nRatio = wRatio / denom,
        nVol = wVol / denom,
        nAccel = wAccel / denom,
    )
}

private fun combineScores(
    fftScore: Double,
    residualScore: Double,
    ratioNorm: Double,
    volScore: Double,
    accelScore: Double,
    volZ: Double,
    volDynBoost: Double,
): Double {
    val (wVol, wRatio) = dynamicRatioWeight(volZ, volDynBoost)
    val weights = normalizeWeights(
        wFft = W_FFT,
        wRes = W_RES,
        wRatio = wRatio,
        wVol = wVol,
        wAccel = W_ACCEL,
    )
    return weights.nFft * fftScore +
        weights.nRes * residualScore +
        weights.nRatio * ratioNorm +
        weights.nVol * volScore +
        weights.nAccel * accelScore
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
    val n = values.size
    val out = DoubleArray(n)
    if (n == 0) return out

    // 前缀和与前缀平方和：prefixSum[k] = sum(values[0 until k])，长度 n+1，prefixSum[0]=0。
    // 任意闭区间 [from, index] 的统计量由前缀差 O(1) 得到，整体从 O(n*window) 降到 O(n)。
    val prefixSum = DoubleArray(n + 1)
    val prefixSumSq = DoubleArray(n + 1)
    for (i in 0 until n) {
        prefixSum[i + 1] = prefixSum[i] + values[i]
        prefixSumSq[i + 1] = prefixSumSq[i] + values[i] * values[i]
    }

    for (index in values.indices) {
        val from = max(0, index - window + 1)
        val size = index - from + 1
        if (size < minPeriods) {
            out[index] = 0.0
            continue
        }
        val sum = prefixSum[index + 1] - prefixSum[from]
        val sumSq = prefixSumSq[index + 1] - prefixSumSq[from]
        val mean = sum / size
        // 总体方差 = E[x²] - E[x]²，与逐窗口 Σ(x-mean)²/size 数学等价；
        // 钳到 0 防止浮点抵消产生的微小负值。
        val variance = max(0.0, sumSq / size - mean * mean)
        val std = sqrt(variance).takeIf { it > 1e-8 } ?: 1.0
        out[index] = (values[index] - mean) / std
    }
    return out
}

private fun rollingScoreSeries(values: DoubleArray, window: Int, block: (DoubleArray) -> Double): DoubleArray {
    val out = DoubleArray(values.size)
    for (index in values.indices) {
        out[index] = if (index + 1 < window) {
            0.5
        } else {
            val slice = values.copyOfRange(index + 1 - window, index + 1)
            block(slice)
        }
    }
    return out
}

private fun applyOverheatGuardInPlace(
    combinedSeries: DoubleArray,
    threshold: Double,
    days: Int,
    decay: Double,
) {
    val n = combinedSeries.size
    if (n == 0) return

    // 关键语义：原实现窗口 [from, i] 内的计数读取的是「已经过 decay」后的左侧值
    // （索引 < i 在更早迭代里已被衰减覆写），唯独索引 i 自身在计数时仍是原值。
    // decay < 1.0 时一次衰减只会变小，可能把原本 > threshold 的左侧值压到 <= threshold，
    // 因此不能对原始数组建静态前缀计数，必须沿已定型值滚动维护前缀计数。
    // prefixCount[k] = count(已定型的 combinedSeries[0 until k] > threshold)，长度 n+1，prefixCount[0]=0。
    val prefixCount = IntArray(n + 1)
    for (i in 0 until n) {
        val from = max(0, i - days + 1)
        // 左侧 [from, i-1] 取已定型前缀计数，加上当前未衰减的 i 自身贡献，复刻原逐窗口读取语义。
        val leftCount = prefixCount[i] - prefixCount[from]
        val selfCount = if (combinedSeries[i] > threshold) 1 else 0
        val count = leftCount + selfCount
        val ratio = count.toDouble() / (i - from + 1).toDouble().coerceAtLeast(1.0)
        combinedSeries[i] = combinedSeries[i] * decay.pow(ratio)
        // i 衰减定型后并入前缀计数，供后续窗口左侧 O(1) 查询。
        prefixCount[i + 1] = prefixCount[i] + if (combinedSeries[i] > threshold) 1 else 0
    }
}

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
    for (index in x.indices) sum += x[index] * y[index]
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

private fun zScoreOfLast(values: DoubleArray, minPeriods: Int): Double {
    val size = values.size
    if (size < minPeriods) return 0.0
    var sum = 0.0
    for (i in values.indices) sum += values[i]
    val mean = sum / size
    var variance = 0.0
    for (i in values.indices) {
        val diff = values[i] - mean
        variance += diff * diff
    }
    val std = sqrt(variance / size).takeIf { it > 1e-8 } ?: 1.0
    return (values.last() - mean) / std
}

private fun overheatGuardForToday(
    history: List<Double>,
    todayValue: Double,
    threshold: Double,
    days: Int,
    decay: Double,
): Double {
    if (history.isEmpty() || threshold <= 0.0 || days <= 0 || decay >= 1.0) {
        return todayValue
    }
    val window = (history + todayValue).takeLast(days)
    val count = window.count { it > threshold }
    val ratio = count.toDouble() / window.size.coerceAtLeast(1)
    return todayValue * decay.pow(ratio)
}

object MarketSentimentCalculator {

    fun calculate(
        barsBySymbol: Map<String, List<PreparedBar>>,
        requiredHistory: Int,
        volDynBoost: Double = DEFAULT_VOL_DYN_BOOST,
        volCapThresh: Double = SentimentMath.VOL_CAP_THRESH,
        volCapMax: Double = SentimentMath.VOL_CAP_MAX,
        sentOverheatThresh: Double = DEFAULT_SENT_OVERHEAT_THRESH,
        sentOverheatDays: Int = DEFAULT_SENT_OVERHEAT_DAYS,
        sentOverheatDecay: Double = DEFAULT_SENT_OVERHEAT_DECAY,
    ): MarketSentimentSnapshot {
        return process(
            barsBySymbol = barsBySymbol,
            requiredHistory = requiredHistory,
            volDynBoost = volDynBoost,
            volCapThresh = volCapThresh,
            volCapMax = volCapMax,
            sentOverheatThresh = sentOverheatThresh,
            sentOverheatDays = sentOverheatDays,
            sentOverheatDecay = sentOverheatDecay,
        ).second
    }

    fun process(
        barsBySymbol: Map<String, List<PreparedBar>>,
        requiredHistory: Int,
        volDynBoost: Double = DEFAULT_VOL_DYN_BOOST,
        volCapThresh: Double = SentimentMath.VOL_CAP_THRESH,
        volCapMax: Double = SentimentMath.VOL_CAP_MAX,
        sentOverheatThresh: Double = DEFAULT_SENT_OVERHEAT_THRESH,
        sentOverheatDays: Int = DEFAULT_SENT_OVERHEAT_DAYS,
        sentOverheatDecay: Double = DEFAULT_SENT_OVERHEAT_DECAY,
    ): Pair<MarketSentimentRollingState, MarketSentimentSnapshot> {
        if (barsBySymbol.isEmpty()) {
            val snapshot = MarketSentimentSnapshot(
                tradeDate = LocalDate(1970, 1, 1),
                signalBasis = "HFQ",
                sampleSize = 0,
                bullRatio = 0.0,
                fftScore = 0.0,
                residualScore = 0.0,
                marketVol = 0.0,
                volZ = 0.0,
                accelZ = 0.0,
                sentimentExposure = 0.0,
                ratioNorm = 0.0,
                volScore = 0.0,
                accelScore = 0.0,
                absoluteFloor = 0.0,
                volCap = 0.0,
                sufficientHistory = false,
                requiredHistory = requiredHistory,
                reason = "无有效市场数据",
            )
            val state = MarketSentimentRollingState(
                tradeDate = LocalDate(1970, 1, 1),
                signalBasis = "HFQ",
                sampleCodes = emptyList(),
                symbolStates = emptyList(),
                bullRatioHistory = emptyList(),
                marketVolHistory = emptyList(),
                accelHistory = emptyList(),
                combinedHistory = emptyList(),
                totalDays = 0,
            )
            return state to snapshot
        }

        val alignedBarsBySymbol = alignBarsBySymbol(barsBySymbol)
        val commonDates = alignedBarsBySymbol.values.firstOrNull()?.map { it.date }.orEmpty()

        if (commonDates.size < requiredHistory) {
            val latestDate = commonDates.lastOrNull() ?: LocalDate(1970, 1, 1)
            val snapshot = MarketSentimentSnapshot(
                tradeDate = latestDate,
                signalBasis = alignedBarsBySymbol.values.firstOrNull()?.firstOrNull()?.signalBasis?.name ?: "HFQ",
                sampleSize = alignedBarsBySymbol.size,
                bullRatio = 0.0,
                fftScore = 0.0,
                residualScore = 0.0,
                marketVol = 0.0,
                volZ = 0.0,
                accelZ = 0.0,
                sentimentExposure = 0.0,
                ratioNorm = 0.0,
                volScore = 0.0,
                accelScore = 0.0,
                absoluteFloor = 0.0,
                volCap = 0.0,
                sufficientHistory = false,
                requiredHistory = requiredHistory,
                reason = "共同交易日不足: ${commonDates.size} < $requiredHistory",
            )
            val state = MarketSentimentRollingState(
                tradeDate = latestDate,
                signalBasis = alignedBarsBySymbol.values.firstOrNull()?.firstOrNull()?.signalBasis?.name ?: "HFQ",
                sampleCodes = alignedBarsBySymbol.keys.toList(),
                symbolStates = emptyList(),
                bullRatioHistory = emptyList(),
                marketVolHistory = emptyList(),
                accelHistory = emptyList(),
                combinedHistory = emptyList(),
                totalDays = commonDates.size,
            )
            return state to snapshot
        }

        val symbols = alignedBarsBySymbol.keys.toList()
        val seriesList = symbols.map(alignedBarsBySymbol::getValue)
        val symbolStates = seriesList.mapIndexed { symbolIndex, bars ->
            val firstBar = bars.first()
            SymbolSentimentState(
                tsCode = symbols[symbolIndex],
                emaShort = firstBar.close,
                emaLong = firstBar.close,
                prevClose = firstBar.close,
                recentReturns = List(20) { 0.0 },
                nextReturnIndex = 0,
                returnWindowSize = 0,
                returnSum = 0.0,
                returnSumSq = 0.0,
            )
        }.toMutableList()

        val ratioSeries = DoubleArray(commonDates.size)
        val marketVolSeries = DoubleArray(commonDates.size)

        ratioSeries[0] = 0.0
        marketVolSeries[0] = 0.0

        for (index in 1 until commonDates.size) {
            var bullishCount = 0
            var volSum = 0.0
            var volCount = 0

            for (symbolIndex in seriesList.indices) {
                val close = seriesList[symbolIndex][index].close
                val newState = symbolStates[symbolIndex].step(close)
                symbolStates[symbolIndex] = newState
                if (newState.emaShort > newState.emaLong) {
                    bullishCount += 1
                }

                val std = newState.standardDeviation()
                if (std > 0.0) {
                    volSum += std
                    volCount += 1
                }
            }

            ratioSeries[index] = bullishCount.toDouble() / seriesList.size.toDouble()
            marketVolSeries[index] = if (volCount == 0) 0.0 else volSum / volCount
        }

        val ratioNormSeries = DoubleArray(ratioSeries.size) { i -> SentimentMath.normalizeBullRatio(ratioSeries[i]) }
        val fftScoreSeries = rollingScoreSeries(ratioSeries, FFT_WINDOW) { window -> fftScore(window) }
        val residualScoreSeries = rollingScoreSeries(ratioSeries, RES_WINDOW) { window -> residualScore(window) }
        val volZSeries = zScoreSeries(marketVolSeries, Z_WINDOW, 60)
        val accelSeries = emaSeries(differenceSeries(ratioSeries), ACCEL_EMA_SPAN)
        val accelZSeries = zScoreSeries(accelSeries, Z_WINDOW, 60)

        val combinedSeries = DoubleArray(ratioSeries.size)
        for (i in combinedSeries.indices) {
            val bullRatio = ratioSeries[i]
            val ratioNorm = ratioNormSeries[i]
            val volZ = volZSeries[i]
            val accelZ = accelZSeries[i]
            val volScore = SentimentMath.sigmoid(-volZ)
            val accelScore = SentimentMath.sigmoid(2.0 * accelZ)

            val fftValid = i + 1 >= FFT_WINDOW
            val resValid = i + 1 >= RES_WINDOW

            val combined = when {
                fftValid && resValid -> {
                    combineScores(
                        fftScore = fftScoreSeries[i],
                        residualScore = residualScoreSeries[i],
                        ratioNorm = ratioNorm,
                        volScore = volScore,
                        accelScore = accelScore,
                        volZ = volZ,
                        volDynBoost = volDynBoost,
                    )
                }

                fftValid && !resValid -> {
                    val (wVol, wRatio) = dynamicRatioWeight(volZ, volDynBoost)
                    val weights = normalizeWeights(
                        wFft = W_FFT,
                        wRes = 0.0,
                        wRatio = wRatio,
                        wVol = wVol,
                        wAccel = W_ACCEL,
                    )
                    weights.nFft * fftScoreSeries[i] +
                        weights.nRatio * ratioNorm +
                        weights.nVol * volScore +
                        weights.nAccel * accelScore
                }

                else -> {
                    ratioNorm
                }
            }

            var guarded = combined * if (bullRatio >= SentimentMath.ABSOLUTE_FLOOR) 1.0 else 0.0
            combinedSeries[i] = guarded
        }

        if (sentOverheatThresh > 0.0 && sentOverheatDays > 0 && sentOverheatDecay < 1.0) {
            applyOverheatGuardInPlace(
                combinedSeries = combinedSeries,
                threshold = sentOverheatThresh,
                days = sentOverheatDays,
                decay = sentOverheatDecay,
            )
        }

        if (volCapThresh > 0.0) {
            for (i in combinedSeries.indices) {
                val volZ = volZSeries[i]
                val excess = max(0.0, volZ - volCapThresh)
                val cap = volCapMax + (1.0 - volCapMax) / (1.0 + 2.0 * excess)
                combinedSeries[i] = min(combinedSeries[i], cap)
            }
        }

        val lastIdx = combinedSeries.lastIndex
        val snapshotDate = commonDates.last()
        val bullRatio = ratioSeries[lastIdx]
        val fftScore = fftScoreSeries[lastIdx]
        val residualScore = residualScoreSeries[lastIdx]
        val marketVol = marketVolSeries[lastIdx]
        val volZ = volZSeries[lastIdx]
        val accelZ = accelZSeries[lastIdx]
        val ratioNorm = SentimentMath.normalizeBullRatio(bullRatio)
        val volScore = SentimentMath.sigmoid(-volZ)
        val accelScore = SentimentMath.sigmoid(2.0 * accelZ)
        val absoluteFloor = if (bullRatio >= SentimentMath.ABSOLUTE_FLOOR) 1.0 else 0.0
        val volCap = SentimentMath.calculateVolCap(volZ)
        val exposure = combinedSeries[lastIdx].coerceIn(0.0, 1.0)

        val snapshot = MarketSentimentSnapshot(
            tradeDate = snapshotDate,
            signalBasis = alignedBarsBySymbol.values.firstOrNull()?.firstOrNull()?.signalBasis?.name ?: "HFQ",
            sampleSize = alignedBarsBySymbol.size,
            bullRatio = bullRatio,
            fftScore = fftScore,
            residualScore = residualScore,
            marketVol = marketVol,
            volZ = volZ,
            accelZ = accelZ,
            sentimentExposure = exposure,
            ratioNorm = ratioNorm,
            volScore = volScore,
            accelScore = accelScore,
            absoluteFloor = absoluteFloor,
            volCap = volCap,
            sufficientHistory = true,
            requiredHistory = requiredHistory,
            reason = null,
        )

        val state = MarketSentimentRollingState(
            tradeDate = snapshotDate,
            signalBasis = snapshot.signalBasis,
            sampleCodes = symbols,
            symbolStates = symbolStates,
            bullRatioHistory = ratioSeries.toList().takeLast(Z_WINDOW),
            marketVolHistory = marketVolSeries.toList().takeLast(Z_WINDOW),
            accelHistory = accelSeries.toList().takeLast(Z_WINDOW),
            combinedHistory = combinedSeries.toList().takeLast(Z_WINDOW),
            totalDays = commonDates.size,
        )
        return state to snapshot
    }

    fun calculate(
        state: MarketSentimentRollingState,
        todayBarsBySymbol: Map<String, PreparedBar>,
        tradeDate: LocalDate,
        requiredHistory: Int,
        volDynBoost: Double = DEFAULT_VOL_DYN_BOOST,
        volCapThresh: Double = SentimentMath.VOL_CAP_THRESH,
        volCapMax: Double = SentimentMath.VOL_CAP_MAX,
        sentOverheatThresh: Double = DEFAULT_SENT_OVERHEAT_THRESH,
        sentOverheatDays: Int = DEFAULT_SENT_OVERHEAT_DAYS,
        sentOverheatDecay: Double = DEFAULT_SENT_OVERHEAT_DECAY,
    ): Pair<MarketSentimentRollingState, MarketSentimentSnapshot> =
        calculateInternal(
            state = state,
            tradeDate = tradeDate,
            requiredHistory = requiredHistory,
            volDynBoost = volDynBoost,
            volCapThresh = volCapThresh,
            volCapMax = volCapMax,
            sentOverheatThresh = sentOverheatThresh,
            sentOverheatDays = sentOverheatDays,
            sentOverheatDecay = sentOverheatDecay,
            resolveClose = { tsCode, prev ->
                when {
                    prev == null -> null
                    else -> todayBarsBySymbol[tsCode]?.close
                }
            }
        )

    fun calculateStrict(
        state: MarketSentimentRollingState,
        observedClosesBySymbol: Map<String, Double>,
        tradeDate: LocalDate,
        requiredHistory: Int,
        volDynBoost: Double = DEFAULT_VOL_DYN_BOOST,
        volCapThresh: Double = SentimentMath.VOL_CAP_THRESH,
        volCapMax: Double = SentimentMath.VOL_CAP_MAX,
        sentOverheatThresh: Double = DEFAULT_SENT_OVERHEAT_THRESH,
        sentOverheatDays: Int = DEFAULT_SENT_OVERHEAT_DAYS,
        sentOverheatDecay: Double = DEFAULT_SENT_OVERHEAT_DECAY,
    ): Pair<MarketSentimentRollingState, MarketSentimentSnapshot> =
        calculateInternal(
            state = state,
            tradeDate = tradeDate,
            requiredHistory = requiredHistory,
            volDynBoost = volDynBoost,
            volCapThresh = volCapThresh,
            volCapMax = volCapMax,
            sentOverheatThresh = sentOverheatThresh,
            sentOverheatDays = sentOverheatDays,
            sentOverheatDecay = sentOverheatDecay,
            resolveClose = { tsCode, prev ->
                requireNotNull(prev) { "STRICT_STATE_MISSING:$tsCode" }
                observedClosesBySymbol[tsCode] ?: prev.prevClose
            }
        )

    private fun calculateInternal(
        state: MarketSentimentRollingState,
        tradeDate: LocalDate,
        requiredHistory: Int,
        volDynBoost: Double,
        volCapThresh: Double,
        volCapMax: Double,
        sentOverheatThresh: Double,
        sentOverheatDays: Int,
        sentOverheatDecay: Double,
        resolveClose: (String, SymbolSentimentState?) -> Double?
    ): Pair<MarketSentimentRollingState, MarketSentimentSnapshot> {
        val sampleCodes = state.sampleCodes.sorted()
        if (sampleCodes.isEmpty()) {
            val snapshot = MarketSentimentSnapshot(
                tradeDate = tradeDate,
                signalBasis = state.signalBasis,
                sampleSize = 0,
                bullRatio = 0.0,
                fftScore = 0.0,
                residualScore = 0.0,
                marketVol = 0.0,
                volZ = 0.0,
                accelZ = 0.0,
                sentimentExposure = 0.0,
                ratioNorm = 0.0,
                volScore = 0.0,
                accelScore = 0.0,
                absoluteFloor = 0.0,
                volCap = 0.0,
                sufficientHistory = false,
                requiredHistory = requiredHistory,
                reason = "样本为空",
            )
            return state.copy(tradeDate = tradeDate) to snapshot
        }

        val stateBySymbol = state.symbolStates.associateBy { it.tsCode }
        val nextSymbolStates = mutableListOf<SymbolSentimentState>()
        val skippedSymbolStates = mutableListOf<SymbolSentimentState>()
        var bullishCount = 0
        var volSum = 0.0
        var volCount = 0

        for (tsCode in sampleCodes) {
            val prev = stateBySymbol[tsCode]
            checkNotNull(prev) {
                "[市场情绪] 增量路径缺少 $tsCode 的前一日状态。" +
                    "这通常意味着调用方未正确重建或合并所有样本股状态。"
            }
            val close = resolveClose(tsCode, prev)
            if (close == null) {
                skippedSymbolStates += prev
                continue
            }
            val nextState = prev.step(close)

            nextSymbolStates += nextState
            if (nextState.emaShort > nextState.emaLong) {
                bullishCount += 1
            }
            val std = nextState.standardDeviation()
            if (std > 0.0) {
                volSum += std
                volCount += 1
            }
        }

        val effectiveSampleSize = nextSymbolStates.size.coerceAtLeast(1)
        val bullRatio = bullishCount.toDouble() / effectiveSampleSize.toDouble()
        val marketVol = if (volCount == 0) 0.0 else volSum / volCount

        val nextBullRatioHistory = (state.bullRatioHistory + bullRatio).takeLast(Z_WINDOW)
        val nextMarketVolHistory = (state.marketVolHistory + marketVol).takeLast(Z_WINDOW)

        val ratioArray = nextBullRatioHistory.toDoubleArray()
        val volArray = nextMarketVolHistory.toDoubleArray()

        val ratioNorm = SentimentMath.normalizeBullRatio(bullRatio)

        val fftScore = if (ratioArray.size >= FFT_WINDOW) {
            fftScore(ratioArray.copyOfRange(ratioArray.size - FFT_WINDOW, ratioArray.size))
        } else {
            0.5
        }

        val residualScore = if (ratioArray.size >= RES_WINDOW) {
            residualScore(ratioArray.copyOfRange(ratioArray.size - RES_WINDOW, ratioArray.size))
        } else {
            0.5
        }

        val volZ = zScoreOfLast(volArray, minPeriods = 60)
        val volScore = SentimentMath.sigmoid(-volZ)

        val lastBullRatio = state.bullRatioHistory.lastOrNull() ?: bullRatio
        val diff = bullRatio - lastBullRatio
        val lastAccel = state.accelHistory.lastOrNull()
        val accel = updateEma(lastAccel, diff, ACCEL_EMA_SPAN)
        val nextAccelHistory = (state.accelHistory + accel).takeLast(Z_WINDOW)
        val accelZ = zScoreOfLast(nextAccelHistory.toDoubleArray(), minPeriods = 60)
        val accelScore = SentimentMath.sigmoid(2.0 * accelZ)

        val fftValid = ratioArray.size >= FFT_WINDOW
        val resValid = ratioArray.size >= RES_WINDOW

        val combinedPreGuard = when {
            fftValid && resValid -> {
                combineScores(
                    fftScore = fftScore,
                    residualScore = residualScore,
                    ratioNorm = ratioNorm,
                    volScore = volScore,
                    accelScore = accelScore,
                    volZ = volZ,
                    volDynBoost = volDynBoost,
                )
            }

            fftValid && !resValid -> {
                val (wVol, wRatio) = dynamicRatioWeight(volZ, volDynBoost)
                val weights = normalizeWeights(
                    wFft = W_FFT,
                    wRes = 0.0,
                    wRatio = wRatio,
                    wVol = wVol,
                    wAccel = W_ACCEL,
                )
                weights.nFft * fftScore +
                    weights.nRatio * ratioNorm +
                    weights.nVol * volScore +
                    weights.nAccel * accelScore
            }

            else -> ratioNorm
        }

        var guarded = combinedPreGuard * if (bullRatio >= SentimentMath.ABSOLUTE_FLOOR) 1.0 else 0.0

        guarded = overheatGuardForToday(
            history = state.combinedHistory,
            todayValue = guarded,
            threshold = sentOverheatThresh,
            days = sentOverheatDays,
            decay = sentOverheatDecay,
        )

        val finalCombined = if (volCapThresh > 0.0) {
            val excess = max(0.0, volZ - volCapThresh)
            val cap = volCapMax + (1.0 - volCapMax) / (1.0 + 2.0 * excess)
            min(guarded, cap)
        } else {
            guarded
        }

        val exposure = finalCombined.coerceIn(0.0, 1.0)

        val absoluteFloor = if (bullRatio >= SentimentMath.ABSOLUTE_FLOOR) 1.0 else 0.0
        val volCap = SentimentMath.calculateVolCap(volZ)

        val snapshot = MarketSentimentSnapshot(
            tradeDate = tradeDate,
            signalBasis = state.signalBasis,
            sampleSize = effectiveSampleSize,
            bullRatio = bullRatio,
            fftScore = fftScore,
            residualScore = residualScore,
            marketVol = marketVol,
            volZ = volZ,
            accelZ = accelZ,
            sentimentExposure = exposure,
            ratioNorm = ratioNorm,
            volScore = volScore,
            accelScore = accelScore,
            absoluteFloor = absoluteFloor,
            volCap = volCap,
            sufficientHistory = true,
            requiredHistory = requiredHistory,
            reason = null,
        )

        val allSymbolStates = nextSymbolStates + skippedSymbolStates
        val nextState = MarketSentimentRollingState(
            tradeDate = tradeDate,
            signalBasis = state.signalBasis,
            sampleCodes = allSymbolStates.map { it.tsCode }.sorted(),
            symbolStates = allSymbolStates,
            bullRatioHistory = nextBullRatioHistory,
            marketVolHistory = nextMarketVolHistory,
            accelHistory = nextAccelHistory,
            combinedHistory = (state.combinedHistory + finalCombined).takeLast(Z_WINDOW),
            totalDays = state.totalDays + 1,
        )
        return nextState to snapshot
    }

    fun rebuildSymbolState(tsCode: String, bars: List<PreparedBar>): SymbolSentimentState {
        if (bars.isEmpty()) error("rebuildSymbolState 需要非空 bars | tsCode=$tsCode")
        val initial = SymbolSentimentState(
            tsCode = tsCode,
            emaShort = bars.first().close,
            emaLong = bars.first().close,
            prevClose = bars.first().close,
            recentReturns = List(20) { 0.0 },
            nextReturnIndex = 0,
            returnWindowSize = 0,
            returnSum = 0.0,
            returnSumSq = 0.0,
        )
        var state = initial
        for (bar in bars.drop(1)) {
            state = state.step(bar.close)
        }
        return state
    }

    fun alignBarsBySymbol(barsBySymbol: Map<String, List<PreparedBar>>): LinkedHashMap<String, List<PreparedBar>> {
        val entries = barsBySymbol.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.key }
        if (entries.isEmpty()) return linkedMapOf()

        val allDates = entries.flatMap { it.value.map { bar -> bar.date } }.toSortedSet().toList()

        val aligned = linkedMapOf<String, List<PreparedBar>>()
        for ((symbol, bars) in entries) {
            val byDate = bars.associateBy { it.date }
            val matched = ArrayList<PreparedBar>(allDates.size)
            var lastValidBar = bars.firstOrNull()
            for (date in allDates) {
                val bar = byDate[date]
                if (bar != null) {
                    lastValidBar = bar
                    matched.add(bar)
                } else if (lastValidBar != null) {
                    matched.add(lastValidBar.copy(date = date))
                }
            }
            if (matched.isNotEmpty()) {
                aligned[symbol] = matched
            }
        }
        return aligned
    }
}
