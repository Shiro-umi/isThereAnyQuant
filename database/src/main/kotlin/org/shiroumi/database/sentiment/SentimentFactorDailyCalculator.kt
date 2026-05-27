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
    val highQfq: Double,
    val lowQfq: Double,
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
    val limitUpTotal: Int,
    val triggered: Int,
    val limitDown: Int,
    val consecutiveMax: Int,
    val consecutiveCount: Int,
    val limitUpTsCodes: Set<String> = emptySet(),
    val consecutiveTsCodes: Set<String> = emptySet(),
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

        val rows = mutableListOf<DailySeed>()
        for ((tradeDate, dayFacts) in byDate) {
            val valid = dayFacts.filter { it.isTradableOn(tradeDate) }
            if (valid.isEmpty()) continue
            val limit = limitByDate[tradeDate]
            val pctValues = valid.map { it.pctNorm }
            val currentLimitUp = limit?.limitUpTsCodes.orEmpty()
            rows += DailySeed(
                tradeDate = tradeDate,
                a1 = valid.weightedMean { it.pctNorm },
                a3 = marketVolumeChange(valid),
                a5Base = limit?.limitUpClean?.toDouble() ?: 0.0,
                a6Base = limit?.limitDown?.toDouble() ?: 0.0,
                bucketReturns = bucketValues(valid, ValueKind.RETURN),
                bucketVolumeChanges = bucketValues(valid, ValueKind.VOLUME_CHANGE),
                b1 = weightedSkew(valid),
                b3 = weightedStd(valid),
                b4 = pctValues.count { it > 0.0 }.toDouble() / pctValues.size,
                b5 = pctValues.count { it > STRONG_MOVE_THRESHOLD }.toDouble() / pctValues.size,
                b6 = pctValues.count { it < -STRONG_MOVE_THRESHOLD }.toDouble() / pctValues.size,
                e1 = valid.weightedMean { it.amplitude },
                c1 = if (limit != null && limit.triggered > 0) {
                    limit.limitUpClean.toDouble() / limit.triggered
                } else {
                    null
                },
                c2 = limit?.consecutiveMax?.toDouble(),
                c3 = limit?.consecutiveCount?.toDouble(),
                c4 = recentLimitReturn(valid, rows, lookback = 3),
                c5 = limit?.let { it.limitUpClean.toDouble() / kotlin.math.max(it.limitDown, 1).toDouble() },
                c6 = currentConsecutiveReturn(valid, limit),
                c7 = previousBreakRate(rows.lastOrNull()?.limitUpTsCodes, currentLimitUp),
                y3Raw = (limit?.let { it.limitUpClean - it.limitDown } ?: 0).toDouble() / valid.size,
                limitUpTsCodes = currentLimitUp,
            )
        }

        val a1Ema = ema(rows.map { it.a1 }, span = 3)
        val a3Ema = ema(rows.map { it.a3 }, span = 3)
        val a5Ema = ema(rows.map { it.a5Base }, span = 5)
        val a6Ema = ema(rows.map { it.a6Base }, span = 5)
        val b3Ema = ema(rows.map { it.b3 }, span = 3)
        val e1Ema = ema(rows.map { it.e1 }, span = 5)
        val c2Ema = ema(rows.map { it.c2 }, span = 5)
        val d1Ema = ema(rows.map { it.a1 }, span = 5)
        val d2Ema = ema(rows.map { it.a1 }, span = 10)

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
                "B1" to row.b1,
                "B3" to row.b3,
                "B3p" to b3Ema[index]?.let { ema -> row.b3?.minus(ema) },
                "B4" to row.b4,
                "B5" to row.b5,
                "B6" to row.b6,
                "B7" to breadthPersistence(rows, index),
                "C1" to row.c1,
                "C2" to row.c2,
                "C2p" to c2Ema[index],
                "C3" to row.c3,
                "C4" to row.c4,
                "C5" to row.c5,
                "C6" to row.c6,
                "C7" to row.c7,
                "D1" to d1Ema[index],
                "D2" to d2Ema[index],
                "D3" to d2Ema[index]?.let { d2 -> row.a1?.minus(d2) },
                "D4" to percentileRank(rows.map { it.a1 }, index, window = 20),
                "D5" to divergenceSignal(row.a1, row.a3),
                "D6" to signedStreak(rows.map { it.a1 }, index),
                "D7" to momentumDecaySignal(rows.map { it.a1 }, index),
                "E1" to row.e1,
                "E2" to e1Ema[index]?.let { ema -> row.e1?.minus(ema) },
            )
            SentimentFactorDailyRecord(
                tradeDate = row.tradeDate,
                factors = factors,
                y1Raw = row.a1,
                y2Raw = row.b4,
                y3Raw = row.y3Raw,
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

    private val SentimentStockDailyFact.amplitude: Double?
        get() = previousCloseQfq?.takeIf { it > 0.0 }?.let { (highQfq - lowQfq) / it }

    private fun weightedStd(facts: List<SentimentStockDailyFact>): Double? {
        val mean = facts.weightedMean { it.pctNorm } ?: return null
        val variance = facts.weightedMean {
            val delta = it.pctNorm - mean
            delta * delta
        } ?: return null
        return kotlin.math.sqrt(variance)
    }

    private fun weightedSkew(facts: List<SentimentStockDailyFact>): Double? {
        val mean = facts.weightedMean { it.pctNorm } ?: return null
        val std = weightedStd(facts) ?: return null
        if (std == 0.0) return 0.0
        val thirdMoment = facts.weightedMean {
            val normalized = (it.pctNorm - mean) / std
            normalized * normalized * normalized
        } ?: return null
        return thirdMoment
    }

    private fun breadthPersistence(rows: List<DailySeed>, index: Int): Double? {
        if (index < 2) return null
        val values = rows.subList(index - 2, index + 1).map { it.b4 ?: return null }
        return values.sumOf { it - 0.5 }
    }

    private fun recentLimitReturn(
        facts: List<SentimentStockDailyFact>,
        previousRows: List<DailySeed>,
        lookback: Int,
    ): Double? {
        val recentLimitCodes = previousRows
            .takeLast(lookback)
            .flatMap { it.limitUpTsCodes }
            .toSet()
        if (recentLimitCodes.isEmpty()) return null
        return facts.filter { it.tsCode in recentLimitCodes }.weightedMean { it.pctNorm }
    }

    private fun currentConsecutiveReturn(
        facts: List<SentimentStockDailyFact>,
        limit: SentimentLimitDailySummary?,
    ): Double? {
        val codes = limit?.consecutiveTsCodes.orEmpty()
        if (codes.isEmpty()) return null
        return facts.filter { it.tsCode in codes }.weightedMean { it.pctNorm }
    }

    private fun previousBreakRate(
        previousLimitUp: Set<String>?,
        currentLimitUp: Set<String>,
    ): Double? {
        val previous = previousLimitUp.orEmpty()
        if (previous.isEmpty()) return null
        return previous.count { it !in currentLimitUp }.toDouble() / previous.size
    }

    private fun percentileRank(values: List<Double?>, index: Int, window: Int): Double? {
        val current = values[index] ?: return null
        val sample = values.subList(kotlin.math.max(0, index - window + 1), index + 1).filterNotNull()
        if (sample.isEmpty()) return null
        val rank = sample.count { it <= current }
        return rank.toDouble() / sample.size
    }

    private fun divergenceSignal(a1: Double?, a3: Double?): Double? {
        if (a1 == null || a3 == null) return null
        return if (sign(a1) * sign(a3) < 0) 1.0 else 0.0
    }

    private fun signedStreak(values: List<Double?>, index: Int): Double? {
        val currentSign = sign(values[index] ?: return null)
        if (currentSign == 0) return 0.0
        var streak = 0
        var cursor = index
        while (cursor >= 0 && sign(values[cursor]) == currentSign) {
            streak++
            cursor--
        }
        return streak.toDouble() * currentSign
    }

    private fun momentumDecaySignal(values: List<Double?>, index: Int): Double? {
        if (index < 2) return null
        val current = values[index] ?: return null
        val previous = values[index - 1] ?: return null
        val beforePrevious = values[index - 2] ?: return null
        return when {
            current > 0.0 && previous > 0.0 && beforePrevious > 0.0 &&
                current < previous && previous < beforePrevious -> 1.0
            current < 0.0 && previous < 0.0 && beforePrevious < 0.0 &&
                current > previous && previous > beforePrevious -> -1.0
            else -> 0.0
        }
    }

    private fun sign(value: Double?): Int =
        when {
            value == null -> 0
            value > 0.0 -> 1
            value < 0.0 -> -1
            else -> 0
        }

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

    private data class DailySeed(
        val tradeDate: LocalDate,
        val a1: Double?,
        val a3: Double?,
        val a5Base: Double,
        val a6Base: Double,
        val bucketReturns: Map<MarketCapBucket, Double?>,
        val bucketVolumeChanges: Map<MarketCapBucket, Double?>,
        val b1: Double?,
        val b3: Double?,
        val b4: Double?,
        val b5: Double?,
        val b6: Double?,
        val e1: Double?,
        val c1: Double?,
        val c2: Double?,
        val c3: Double?,
        val c4: Double?,
        val c5: Double?,
        val c6: Double?,
        val c7: Double?,
        val y3Raw: Double?,
        val limitUpTsCodes: Set<String>,
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

    private const val STRONG_MOVE_THRESHOLD = 0.05
}
