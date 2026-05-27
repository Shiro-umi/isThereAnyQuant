package org.shiroumi.database.sentiment

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

data class SentimentStockDailyFact(
    val tradeDate: LocalDate,
    val tsCode: String,
    val name: String,
    val listDate: LocalDate?,
    val delistDate: LocalDate?,
    val closeQfq: Double,
    val previousCloseQfq: Double?,
    val volumeQfq: Double,
    val previousVolumeQfq: Double?,
    val turnoverReal: Double,
    val previousTurnoverReal: Double?,
    val mvCirc: Double,
)

data class SentimentLimitDailySummary(
    val tradeDate: LocalDate,
    val limitUpClean: Int,
    val limitDown: Int,
)

object SentimentFactorDailyCalculator {
    fun calculate(
        facts: List<SentimentStockDailyFact>,
        limitSummaries: List<SentimentLimitDailySummary>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<SentimentFactorDailyRecord> {
        val limitByDate = limitSummaries.associateBy { it.tradeDate }
        val byDate = facts
            .filter { it.tradeDate >= startDate && it.tradeDate <= endDate }
            .groupBy { it.tradeDate }
            .toSortedMap()

        val rows = mutableListOf<DailyASeed>()
        for ((tradeDate, dayFacts) in byDate) {
            val valid = dayFacts.filter { it.isTradableOn(tradeDate) }
            if (valid.isEmpty()) continue
            val limit = limitByDate[tradeDate]
            rows += DailyASeed(
                tradeDate = tradeDate,
                a1 = valid.weightedMean { it.pctNorm },
                a3 = marketVolumeChange(valid),
                a5Base = limit?.limitUpClean?.toDouble() ?: 0.0,
                a6Base = limit?.limitDown?.toDouble() ?: 0.0,
                bucketReturns = bucketValues(valid, ValueKind.RETURN),
                bucketVolumeChanges = bucketValues(valid, ValueKind.VOLUME_CHANGE),
            )
        }

        val a1Ema = ema(rows.map { it.a1 }, span = 3)
        val a3Ema = ema(rows.map { it.a3 }, span = 3)
        val a5Ema = ema(rows.map { it.a5Base }, span = 5)
        val a6Ema = ema(rows.map { it.a6Base }, span = 5)

        return rows.mapIndexed { index, row ->
            val factors = linkedMapOf<String, Double?>(
                "A1" to row.a1,
                "A2" to diff(a1Ema, index),
                "A3" to row.a3,
                "A4" to diff(a3Ema, index),
                "A5" to a5Ema[index]?.let { row.a5Base - it },
                "A6" to a6Ema[index]?.let { row.a6Base - it },
                "A7" to row.bucketReturns[MarketCapBucket.SMALL],
                "A8" to row.bucketReturns[MarketCapBucket.MID],
                "A9a" to row.bucketReturns[MarketCapBucket.MID_LARGE],
                "A9b" to row.bucketReturns[MarketCapBucket.LARGE],
                "A10" to row.bucketVolumeChanges[MarketCapBucket.SMALL],
                "A11" to row.bucketVolumeChanges[MarketCapBucket.MID],
                "A11a" to row.bucketVolumeChanges[MarketCapBucket.MID_LARGE],
                "A12" to row.bucketVolumeChanges[MarketCapBucket.LARGE],
            )
            SentimentFactorDailyRecord(
                tradeDate = row.tradeDate,
                factors = factors,
                y1Raw = row.a1,
                y2Raw = null,
                y3Raw = null,
                yComposite = null,
            )
        }
    }

    private fun SentimentStockDailyFact.isTradableOn(tradeDate: LocalDate): Boolean {
        if (previousCloseQfq == null || previousCloseQfq <= 0.0 || closeQfq <= 0.0) return false
        if (volumeQfq <= 0.0 || previousVolumeQfq == null || previousVolumeQfq <= 0.0) return false
        if (mvCirc <= 0.0) return false
        if (listDate != null && listDate.daysUntil(tradeDate) <= 20) return false
        if (delistDate != null && tradeDate.daysUntil(delistDate) <= 30) return false
        return true
    }

    private val SentimentStockDailyFact.pctNorm: Double
        get() {
            val pctRaw = closeQfq / previousCloseQfq!! - 1.0
            val factor = when {
                name.contains("ST", ignoreCase = true) -> 2.0
                tsCode.endsWith(".BJ") -> 1.0 / 3.0
                tsCode.startsWith("688") && tsCode.endsWith(".SH") -> 0.5
                tsCode.startsWith("30") && tsCode.endsWith(".SZ") -> 0.5
                else -> 1.0
            }
            return (pctRaw * factor).coerceIn(-0.10, 0.10)
        }

    private fun marketVolumeChange(facts: List<SentimentStockDailyFact>): Double? {
        val current = facts.sumOf { it.volumeQfq }
        val previous = facts.sumOf { it.previousVolumeQfq ?: 0.0 }
        return if (previous > 0.0) current / previous - 1.0 else null
    }

    private fun bucketValues(
        facts: List<SentimentStockDailyFact>,
        kind: ValueKind,
    ): Map<MarketCapBucket, Double?> =
        MarketCapBucket.entries.associateWith { bucket ->
            val bucketFacts = facts.filter { bucket.matches(it.previousTurnoverReal) }
            if (bucketFacts.isEmpty()) return@associateWith null
            bucketFacts.weightedMean {
                when (kind) {
                    ValueKind.RETURN -> it.pctNorm
                    ValueKind.VOLUME_CHANGE -> it.volumeChange
                }
            }
        }

    private val SentimentStockDailyFact.volumeChange: Double?
        get() = previousVolumeQfq?.takeIf { it > 0.0 }?.let { volumeQfq / it - 1.0 }

    private fun List<SentimentStockDailyFact>.weightedMean(value: (SentimentStockDailyFact) -> Double?): Double? {
        var weighted = 0.0
        var totalWeight = 0.0
        for (fact in this) {
            val v = value(fact) ?: continue
            weighted += v * fact.mvCirc
            totalWeight += fact.mvCirc
        }
        return if (totalWeight > 0.0) weighted / totalWeight else null
    }

    private fun ema(values: List<Double?>, span: Int): List<Double?> {
        val alpha = 2.0 / (span + 1.0)
        var previous: Double? = null
        return values.map { value ->
            if (value == null) {
                previous
            } else {
                val current = previous?.let { alpha * value + (1.0 - alpha) * it } ?: value
                previous = current
                current
            }
        }
    }

    private fun diff(values: List<Double?>, index: Int): Double? {
        if (index == 0) return null
        val current = values[index] ?: return null
        val previous = values[index - 1] ?: return null
        return current - previous
    }

    private data class DailyASeed(
        val tradeDate: LocalDate,
        val a1: Double?,
        val a3: Double?,
        val a5Base: Double,
        val a6Base: Double,
        val bucketReturns: Map<MarketCapBucket, Double?>,
        val bucketVolumeChanges: Map<MarketCapBucket, Double?>,
    )

    private enum class ValueKind {
        RETURN,
        VOLUME_CHANGE,
    }

    private enum class MarketCapBucket {
        SMALL,
        MID,
        MID_LARGE,
        LARGE;

        fun matches(previousTurnoverReal: Double?): Boolean {
            val turnover = previousTurnoverReal ?: return false
            return when (this) {
                SMALL -> turnover >= 0.0 && turnover < 1_000_000_000.0
                MID -> turnover >= 1_000_000_000.0 && turnover < 5_000_000_000.0
                MID_LARGE -> turnover >= 5_000_000_000.0 && turnover < 10_000_000_000.0
                LARGE -> turnover >= 10_000_000_000.0
            }
        }
    }
}
