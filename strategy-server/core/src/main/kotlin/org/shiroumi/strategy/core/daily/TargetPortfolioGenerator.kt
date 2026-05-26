package org.shiroumi.strategy.core.daily

import kotlinx.datetime.LocalDate

private const val TOP_N = 5
private const val W_TREND = 0.17
private const val W_MOM = 0.25
private const val W_VOL = 0.10
private const val W_AMOM = 0.48
private const val SENT_FACTOR_BOOST = 0.5

data class TargetPosition(
    val tradeDate: LocalDate,
    val targetDate: LocalDate,
    val tsCode: String,
    val selectionScore: Double,
    val selected: Boolean,
    val targetWeight: Double,
    val sentimentExposure: Double,
    val selectionReason: String?,
)

data class PortfolioSelection(
    val factor: StockFactorSnapshot,
    val selectionScore: Double,
)

object TargetPortfolioGenerator {
    fun generate(
        tradeDate: LocalDate,
        targetDate: LocalDate,
        factors: List<StockFactorSnapshot>,
        sentiment: MarketSentimentSnapshot,
    ): List<TargetPosition> {
        val exposure = sentiment.sentimentExposure
        val topN = PortfolioSelectionEngine.selectTopSelections(
            factors = factors,
            sentimentExposure = exposure,
            topN = TOP_N
        )
        val weightPerStock = if (topN.isEmpty() || exposure == 0.0) 0.0 else exposure / topN.size

        return topN.map { selection ->
            val factor = selection.factor
            val isSelected = exposure > 0.0 // 情绪为0时不买入
            TargetPosition(
                tradeDate = tradeDate,
                targetDate = targetDate,
                tsCode = factor.tsCode,
                selectionScore = selection.selectionScore,
                selected = isSelected,
                targetWeight = if (isSelected) weightPerStock else 0.0,
                sentimentExposure = exposure,
                selectionReason = if (exposure == 0.0) "情绪为0强制不选股" else "进入TOP_$TOP_N",
            )
        }
    }
}

object PortfolioSelectionEngine {
    fun selectTopFactors(
        factors: List<StockFactorSnapshot>,
        sentimentExposure: Double,
        topN: Int = TOP_N
    ): List<StockFactorSnapshot> = selectTopSelections(
        factors = factors,
        sentimentExposure = sentimentExposure,
        topN = topN
    ).map { it.factor }

    fun selectTopSelections(
        factors: List<StockFactorSnapshot>,
        sentimentExposure: Double,
        topN: Int = TOP_N
    ): List<PortfolioSelection> {
        val candidates = factors.filter { it.signal && it.sufficientHistory }
        if (candidates.isEmpty()) return emptyList()

        // 对齐 docs/strategy.py：情绪驱动的因子权重动态调整
        // alpha = exposure - 0.5；牛市提高 AMOM，熊市提高 TREND
        val alpha = sentimentExposure - 0.5
        val adjustment = SENT_FACTOR_BOOST * alpha
        var wAmomDyn = maxOf(0.01, W_AMOM * (1.0 + adjustment))
        var wTrendDyn = maxOf(0.01, W_TREND * (1.0 - adjustment))
        var wMomDyn = W_MOM
        var wVolDyn = W_VOL

        // 归一化：保持总权重与原版一致
        val totalOriginal = W_AMOM + W_TREND + W_MOM + W_VOL
        val totalDynamic = wAmomDyn + wTrendDyn + wMomDyn + wVolDyn
        val scale = totalOriginal / totalDynamic.coerceAtLeast(1e-8)
        wAmomDyn *= scale
        wTrendDyn *= scale
        wMomDyn *= scale
        wVolDyn *= scale

        // 对齐 docs/strategy.py：横截面 rank(pct=True) 打分
        val factorByCode = candidates.associateBy { it.tsCode }
        val scores = candidates
            .associate { it.tsCode to wTrendDyn }
            .toMutableMap()

        val momPct = percentileRankMap(
            candidates.mapNotNull { factor ->
                val v = factor.momentum20
                if (v.isFinite()) factor.tsCode to v else null
            },
        )
        momPct.forEach { (tsCode, pct) ->
            scores[tsCode] = scores.getValue(tsCode) + wMomDyn * pct
        }

        val volPct = percentileRankMap(
            candidates.mapNotNull { factor ->
                val v = factor.volRatio520
                if (v.isFinite() && v > 0.0) factor.tsCode to v else null
            },
        )
        volPct.forEach { (tsCode, pct) ->
            scores[tsCode] = scores.getValue(tsCode) + wVolDyn * pct
        }

        val amomPct = percentileRankMap(
            candidates.mapNotNull { factor ->
                val v = factor.amomCombined
                if (v.isFinite()) factor.tsCode to v else null
            },
        )
        amomPct.forEach { (tsCode, pct) ->
            scores[tsCode] = scores.getValue(tsCode) + wAmomDyn * pct
        }

        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(topN)
            .mapNotNull { entry ->
                factorByCode[entry.key]?.let { factor ->
                    PortfolioSelection(factor = factor, selectionScore = entry.value)
                }
            }
    }

    private fun percentileRankMap(entries: List<Pair<String, Double>>): Map<String, Double> {
        if (entries.size < 2) return emptyMap()
        val sorted = entries.sortedBy { it.second }
        val n = sorted.size

        val out = HashMap<String, Double>(n)
        var i = 0
        while (i < n) {
            var j = i
            val v = sorted[i].second
            while (j + 1 < n && sorted[j + 1].second == v) {
                j += 1
            }
            // pandas rank 默认 method=average, 1-based
            val avgRank = (i + 1 + j + 1).toDouble() / 2.0
            val pct = avgRank / n.toDouble()
            for (k in i..j) {
                out[sorted[k].first] = pct
            }
            i = j + 1
        }
        return out
    }
}
