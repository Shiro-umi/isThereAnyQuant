package org.shiroumi.database.data_provider.peak_valley

import org.shiroumi.database.data_provider.model.ExtremePoint
import org.shiroumi.database.data_provider.model.ExtremeType
import org.shiroumi.database.data_provider.model.PeakValleyItem


fun detectExtremePoints(
    rawData: List<PeakValleyItem>,
    smoothWindow: Int = 5,
    lookback: Int = 3
): List<ExtremePoint> {
    val smoothedData = smoothPrices(rawData, smoothWindow)
    calculateDerivatives(smoothedData)
    return findPeaksAndValleys(smoothedData, lookback)
}

fun smoothPrices(peakValleyItem: List<PeakValleyItem>, window: Int = 5): List<PeakValleyItem> {
    return peakValleyItem.mapIndexed { index, data ->
        if (index < window - 1) {
            data.copy(smoothedPrice = data.value) // 前几个点不进行平滑
        } else {
            val sum = peakValleyItem.subList(index - window + 1, index + 1).sumOf { it.value }
            data.copy(smoothedPrice = sum / window)
        }
    }
}

/**
 * 计算一阶和二阶导数（差分）
 */
fun calculateDerivatives(peakValleyItem: List<PeakValleyItem>) {
    for (i in 1 until peakValleyItem.size) {
        // 一阶导数（价格变化率）
        peakValleyItem[i].firstDerivative =
            peakValleyItem[i].smoothedPrice - peakValleyItem[i - 1].smoothedPrice
    }

    for (i in 2 until peakValleyItem.size) {
        // 二阶导数（变化率的变化率）
        peakValleyItem[i].secondDerivative =
            peakValleyItem[i].firstDerivative - peakValleyItem[i - 1].firstDerivative
    }
}

/**
 * 检查是否为局部极值点
 */
private fun isLocalExtreme(
    data: List<PeakValleyItem>,
    currentIndex: Int,
    lookback: Int,
    isPeak: Boolean
): Boolean {
    val currentPrice = data[currentIndex].smoothedPrice

    // 检查前 lookback 个周期
    for (j in 1..lookback) {
        if (currentIndex - j < 0) continue
        val comparePrice = data[currentIndex - j].smoothedPrice
        if (isPeak && currentPrice <= comparePrice) return false
        if (!isPeak && currentPrice >= comparePrice) return false
    }

    // 检查后 lookback 个周期
    for (j in 1..lookback) {
        if (currentIndex + j >= data.size) continue
        val comparePrice = data[currentIndex + j].smoothedPrice
        if (isPeak && currentPrice <= comparePrice) return false
        if (!isPeak && currentPrice >= comparePrice) return false
    }

    return true
}

fun findPeaksAndValleys(
    stockData: List<PeakValleyItem>,
    lookback: Int = 3
): List<ExtremePoint> {
    val extremePoints = mutableListOf<ExtremePoint>()
    for (i in lookback until stockData.size - lookback - 1) {
        val current = stockData[i]
        val next = stockData[i + 1]

        // 检查波峰条件
        if (current.firstDerivative > 0 &&
            next.firstDerivative < 0 &&
            current.secondDerivative < 0
        ) {

            if (isLocalExtreme(stockData, i, lookback, true)) {
                extremePoints.add(
                    ExtremePoint(
                        index = i,
                        price = current.smoothedPrice,
                        type = ExtremeType.PEAK,
                        date = current.date
                    )
                )
            }
        }

        // 检查波谷条件
        if (current.firstDerivative < 0 &&
            next.firstDerivative > 0 &&
            current.secondDerivative > 0
        ) {

            if (isLocalExtreme(stockData, i, lookback, false)) {
                extremePoints.add(
                    ExtremePoint(
                        index = i,
                        price = current.smoothedPrice,
                        type = ExtremeType.VALLEY,
                        date = current.date
                    )
                )
            }
        }
    }

    return extremePoints
}