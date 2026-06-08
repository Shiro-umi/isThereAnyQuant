package org.shiroumi.strategy.core.intraday

import kotlinx.datetime.LocalDate
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.StockFactorSnapshot

/**
 * 盘中目标持仓数据类
 * 包含盘后模型确认组合，以及盘中价格/量能展示指标。
 */
data class TargetPosition(
    val tsCode: String,
    val rankScore: Double,
    val selected: Boolean,
    val targetWeight: Double,
    val sentimentExposure: Double,
    val priceChangePct: Double,
    val volumeRatio: Double,
    val postMarketSelected: Boolean?,
    val postMarketWeight: Double?,
    val action: String?,
    val actionReason: String?
)

/**
 * 盘中目标组合生成器
 *
 * 核心设计原则：
 * 盘中不再维护独立的因子 rank 选股口径。
 * 盘后目标组合由研究模型生成，盘中仅基于实时因子补充价格/量能展示并沿用盘后模型目标。
 *
 * 性能目标：单次组合生成耗时 < 50ms
 */
object IntradayPortfolioGenerator {

    /**
     * 生成盘中目标组合
     *
     * @param tradeDate 交易日期
     * @param timestamp 计算时间戳（毫秒）
     * @param factors 盘中因子快照列表
     * @param sentiment 市场情绪快照
     * @param topN 选股数量，默认5只
     * @param postMarketPositions 盘后模型确认的目标持仓
     * @return 目标持仓列表
     */
    fun generate(
        tradeDate: LocalDate,
        timestamp: Long,
        factors: List<StockFactorSnapshot>,
        sentiment: MarketSentimentSnapshot,
        topN: Int = 5,
        postMarketPositions: List<org.shiroumi.strategy.core.daily.TargetPosition>? = null
    ): List<TargetPosition> {
        val startTime = System.currentTimeMillis()
        val exposure = sentiment.sentimentExposure
        val postMarketSelected = postMarketPositions
            ?.filter { it.selected }
            ?.sortedWith(compareByDescending<org.shiroumi.strategy.core.daily.TargetPosition> { it.selectionScore }.thenBy { it.tsCode })
            ?.take(topN)
            ?: emptyList()
        if (postMarketSelected.isEmpty()) {
            return emptyList()
        }
        val factorByCode = factors.associateBy { it.tsCode }

        val positions = postMarketSelected.map { postMarketPosition ->
            val factor = factorByCode[postMarketPosition.tsCode]
            val priceChangePct = factor?.let { if (it.open == 0.0) 0.0 else (it.close - it.open) / it.open } ?: 0.0
            val volumeRatio = factor?.volRatio520 ?: 0.0

            TargetPosition(
                tsCode = postMarketPosition.tsCode,
                rankScore = postMarketPosition.selectionScore,
                selected = postMarketPosition.selected,
                targetWeight = postMarketPosition.targetWeight,
                sentimentExposure = exposure,
                priceChangePct = priceChangePct,
                volumeRatio = volumeRatio,
                postMarketSelected = true,
                postMarketWeight = postMarketPosition.targetWeight,
                action = null,
                actionReason = null
            )
        }

        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed > 50) {
            println("[WARN] 组合生成耗时超过50ms: ${elapsed}ms")
        }

        return positions
    }
}
