package org.shiroumi.database.sentiment

import kotlinx.datetime.LocalDate

data class SentimentTargetLabels(
    val tradeDate: LocalDate,
    val y1Next1: Double?,
    val y1Next3: Double?,
    val y1Next5: Double?,
    val y2Next1: Double?,
    val y2Next3: Double?,
    val y2Next5: Double?,
    val y3Next1: Double?,
    val y3Next3: Double?,
    val y3Next5: Double?,
    val yCompositeNext1: Double?,
    val yCompositeNext3: Double?,
    val yCompositeNext5: Double?,
    val yMax3: Double?,
    val yMin3: Double?,
    val yMax5: Double?,
    val yMin5: Double?,
    val yDrawdown5: Double?,
    val yDirection3: Double?,
    val yDirection5: Double?,
)

object SentimentTargetLabelCalculator {
    fun build(records: List<SentimentFactorDailyRecord>): List<SentimentTargetLabels> {
        val sorted = records.sortedBy { it.tradeDate }
        val y1 = sorted.map { it.y1Raw }
        val y2 = sorted.map { it.y2Raw }
        val y3 = sorted.map { it.y3Raw }
        val yc = sorted.map { it.yComposite }
        return sorted.indices.map { index ->
            SentimentTargetLabels(
                tradeDate = sorted[index].tradeDate,
                y1Next1 = meanNext(y1, index, 1),
                y1Next3 = meanNext(y1, index, 3),
                y1Next5 = meanNext(y1, index, 5),
                y2Next1 = meanNext(y2, index, 1),
                y2Next3 = meanNext(y2, index, 3),
                y2Next5 = meanNext(y2, index, 5),
                y3Next1 = meanNext(y3, index, 1),
                y3Next3 = meanNext(y3, index, 3),
                y3Next5 = meanNext(y3, index, 5),
                yCompositeNext1 = meanNext(yc, index, 1),
                yCompositeNext3 = meanNext(yc, index, 3),
                yCompositeNext5 = meanNext(yc, index, 5),
                yMax3 = futureWindow(y1, index, 3)?.maxOrNull(),
                yMin3 = futureWindow(y1, index, 3)?.minOrNull(),
                yMax5 = futureWindow(y1, index, 5)?.maxOrNull(),
                yMin5 = futureWindow(y1, index, 5)?.minOrNull(),
                yDrawdown5 = drawdown5(y1, index),
                yDirection3 = sign(meanNext(y1, index, 3)),
                yDirection5 = sign(meanNext(y1, index, 5)),
            )
        }
    }

    private fun meanNext(values: List<Double?>, index: Int, horizon: Int): Double? =
        futureWindow(values, index, horizon)?.average()

    private fun futureWindow(values: List<Double?>, index: Int, horizon: Int): List<Double>? {
        if (index + horizon >= values.size) return null
        val window = values.subList(index + 1, index + horizon + 1)
        if (window.any { it == null }) return null
        return window.filterNotNull()
    }

    private fun drawdown5(values: List<Double?>, index: Int): Double? {
        val window = futureWindow(values, index, 5) ?: return null
        return window.maxOrNull()!! - window.last()
    }

    private fun sign(value: Double?): Double? =
        when {
            value == null -> null
            value > 0.0 -> 1.0
            value < 0.0 -> -1.0
            else -> 0.0
        }
}
