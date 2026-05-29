package org.shiroumi.database.sentiment

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.math.ln

/**
 * 量价因子族（VV/VP）的**市场级基础量序列**计算器。
 *
 * 设计依据见研究文档 `private/research-docs/volume-price-factor-formula.html` §2/§4：
 * - **先聚合后推导**：本计算器只负责「每日全市场聚合成单值」这一步，得到市场级基础量序列；
 *   18 个 VV/VP 因子的「前序序列推导」（斜率/游程/谱熵/相干等）由 research 层在这些序列上完成。
 * - **等权聚合（非市值加权）**：与情绪因子（市值加权 [SentimentFactorDailyCalculator]）口径不同——
 *   量价研究关注的是「市场整体的量能节奏」，每只标的等权计入，不让大市值标的主导。
 *
 * 产出两条基础序列：
 * - `VPM_ret`：全市场对数收益 `ln(close/prevClose)` 的算术均值（价的市场级节奏原料）。
 * - `VPM_turn`：全市场换手率 `turnoverReal/mvCirc` 的算术均值（量的市场级节奏原料，免疫复权与市值规模）。
 *
 * 可交易判定与情绪计算器保持一致口径，避免停牌/次新/退市标的污染聚合。
 */
object VolumePriceMarketCalculator {

    fun calculate(
        facts: List<SentimentStockDailyFact>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<SentimentFactorDailyRecord> {
        val byDate = facts
            .filter { it.tradeDate in startDate..endDate }
            .groupBy { it.tradeDate }
            .toSortedMap()

        return byDate.mapNotNull { (tradeDate, dayFacts) ->
            val valid = dayFacts.filter { it.isTradableOn(tradeDate) }
            if (valid.isEmpty()) return@mapNotNull null
            SentimentFactorDailyRecord(
                tradeDate = tradeDate,
                factors = emptyMap(),
                y1Raw = null,
                y2Raw = null,
                y3Raw = null,
                yComposite = null,
                vpmRet = valid.mean { ln(it.closeQfq / it.previousCloseQfq!!) },
                vpmTurn = valid.mean { it.turnoverReal / it.mvCirc },
            )
        }
    }

    /** 等权（算术）均值，跳过 null/非有限值。 */
    private inline fun List<SentimentStockDailyFact>.mean(value: (SentimentStockDailyFact) -> Double): Double? {
        var sum = 0.0
        var count = 0
        for (fact in this) {
            val v = value(fact)
            if (v.isFinite()) {
                sum += v
                count++
            }
        }
        return if (count > 0) sum / count else null
    }

    /** 与 [SentimentFactorDailyCalculator] 一致的可交易判定口径（停牌/次新/退市过滤）。 */
    private fun SentimentStockDailyFact.isTradableOn(tradeDate: LocalDate): Boolean {
        if (previousCloseQfq == null || previousCloseQfq <= 0.0 || closeQfq <= 0.0) return false
        if (volumeQfq <= 0.0 || previousVolumeQfq == null || previousVolumeQfq <= 0.0) return false
        if (mvCirc <= 0.0) return false
        if (turnoverReal <= 0.0) return false
        if (listDate != null && listDate.daysUntil(tradeDate) <= 20) return false
        if (delistDate != null && tradeDate.daysUntil(delistDate) <= 30) return false
        return true
    }
}
