package org.shiroumi.strategy.core.intraday

import kotlinx.datetime.LocalDate
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.PortfolioSelectionEngine
import org.shiroumi.strategy.core.daily.StockFactorSnapshot

private const val TOP_N = 5
private const val WEIGHT_CHANGE_THRESHOLD = 0.10  // 权重变化阈值 10%

/**
 * 盘中目标持仓数据类
 * 包含选股结果、盘后对比和调仓建议
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
    val action: String?,  // BUY/SELL/HOLD
    val actionReason: String?
)

/**
 * 盘中目标组合生成器
 *
 * 核心设计原则：
 * 1. 复用盘后横截面选股口径
 * 2. 取 TOP_N = 5
 * 4. 计算目标权重 = sentimentExposure / TOP_N
 * 5. 对比盘后持仓，生成调仓建议
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
     * @param postMarketPositions 盘后目标持仓（用于对比），可为null
     * @return 目标持仓列表
     */
    fun generate(
        tradeDate: LocalDate,
        timestamp: Long,
        factors: List<StockFactorSnapshot>,
        sentiment: MarketSentimentSnapshot,
        topN: Int = TOP_N,
        postMarketPositions: List<org.shiroumi.strategy.core.daily.TargetPosition>? = null
    ): List<TargetPosition> {
        val startTime = System.currentTimeMillis()
        val exposure = sentiment.sentimentExposure

        val selectedFactors = PortfolioSelectionEngine.selectTopSelections(
            factors = factors,
            sentimentExposure = exposure,
            topN = topN
        )

        if (selectedFactors.isEmpty()) {
            return emptyList()
        }
        val selectedCodes = selectedFactors.map { it.factor.tsCode }.toSet()

        // 4. 构建盘后持仓映射（用于对比）
        val postMarketMap = postMarketPositions
            ?.filter { it.selected }
            ?.associateBy { it.tsCode }
            ?: emptyMap()
        val postMarketCodes = postMarketMap.keys

        // 5. 计算目标权重
        val weightPerStock = if (selectedFactors.isEmpty() || exposure == 0.0) 0.0 else exposure / selectedFactors.size

        // 6. 生成目标持仓（包含调仓建议）
        val positions = selectedFactors.map { selection ->
            val factor = selection.factor
            val tsCode = factor.tsCode
            val postMarketPosition = postMarketMap[tsCode]
            val postMarketSelected = postMarketPosition?.selected
            val postMarketWeight = postMarketPosition?.targetWeight

            // 计算调仓建议
            val (action, actionReason) = determineAction(
                tsCode = tsCode,
                intradaySelected = true,
                intradayWeight = weightPerStock,
                postMarketSelected = postMarketSelected,
                postMarketWeight = postMarketWeight
            )

            // 计算盘中变化追踪指标
            val priceChangePct = if (factor.open == 0.0) 0.0 else (factor.close - factor.open) / factor.open
            val volumeRatio = factor.volRatio520

            TargetPosition(
                tsCode = tsCode,
                rankScore = selection.selectionScore,
                selected = exposure > 0.0, // 情绪为0时不买入
                targetWeight = if (exposure > 0.0) weightPerStock else 0.0,
                sentimentExposure = exposure,
                priceChangePct = priceChangePct,
                volumeRatio = volumeRatio,
                postMarketSelected = postMarketSelected,
                postMarketWeight = postMarketWeight,
                action = action,
                actionReason = actionReason
            )
        }

        // 7. 处理 SELL 建议（盘中未选中但盘后选中的股票）
        val sellPositions = postMarketCodes
            .filter { it !in selectedCodes }
            .mapNotNull { tsCode ->
                postMarketMap[tsCode]?.let { postMarketPos ->
                    TargetPosition(
                        tsCode = tsCode,
                        rankScore = 0.0,
                        selected = false,
                        targetWeight = 0.0,
                        sentimentExposure = exposure,
                        priceChangePct = 0.0,
                        volumeRatio = 0.0,
                        postMarketSelected = true,
                        postMarketWeight = postMarketPos.targetWeight,
                        action = "SELL",
                        actionReason = "盘后持仓但盘中未选中"
                    )
                }
            }

        // 合并结果
        val allPositions = positions + sellPositions

        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed > 50) {
            println("[WARN] 组合生成耗时超过50ms: ${elapsed}ms")
        }

        return allPositions
    }

    /**
     * 确定调仓建议
     *
     * 逻辑：
     * - BUY: 盘中选中，盘后未选中
     * - SELL: 盘中未选中，盘后选中
     * - HOLD: 盘中盘后都选中，但权重变化 > 10%
     * - null: 无变化
     *
     * @param tsCode 股票代码
     * @param intradaySelected 盘中是否选中
     * @param intradayWeight 盘中目标权重
     * @param postMarketSelected 盘后是否选中
     * @param postMarketWeight 盘后目标权重
     * @return Pair(动作, 原因)
     */
    private fun determineAction(
        tsCode: String,
        intradaySelected: Boolean,
        intradayWeight: Double,
        postMarketSelected: Boolean?,
        postMarketWeight: Double?
    ): Pair<String?, String?> {
        return when {
            // BUY: 盘中选中，盘后未选中
            intradaySelected && postMarketSelected == false -> {
                "BUY" to "新进入TOP_$TOP_N"
            }

            // BUY: 盘中选中，盘后无记录（新上市或首次进入）
            intradaySelected && postMarketSelected == null -> {
                "BUY" to "新进入TOP_$TOP_N"
            }

            // HOLD: 盘中盘后都选中，但权重变化 > 10%
            intradaySelected && postMarketSelected == true -> {
                val weightChange = kotlin.math.abs(intradayWeight - (postMarketWeight ?: 0.0))
                if (weightChange > WEIGHT_CHANGE_THRESHOLD) {
                    "HOLD" to "权重变化 ${formatPercent(weightChange)}，需调仓"
                } else {
                    null to null // 无变化
                }
            }

            // SELL: 盘中未选中，盘后选中（在调用方处理）
            !intradaySelected && postMarketSelected == true -> {
                "SELL" to "跌出TOP_$TOP_N"
            }

            // 其他情况：无变化
            else -> null to null
        }
    }

    /**
     * 批量生成组合（用于高效处理多批次）
     *
     * @param tradeDate 交易日期
     * @param timestamp 计算时间戳
     * @param factorBatches 因子批次列表（按板块或流动性分组）
     * @param sentiment 市场情绪快照
     * @param topN 每批选股数量
     * @return 合并后的目标持仓列表
     */
    fun generateBatch(
        tradeDate: LocalDate,
        timestamp: Long,
        factorBatches: List<List<StockFactorSnapshot>>,
        sentiment: MarketSentimentSnapshot,
        topN: Int = TOP_N
    ): List<TargetPosition> {
        // 合并所有批次
        val allFactors = factorBatches.flatten()
        return generate(tradeDate, timestamp, allFactors, sentiment, topN)
    }

    /**
     * 格式化百分比（避免使用 String.format，JS平台不兼容）
     */
    private fun formatPercent(value: Double): String {
        val rounded = kotlin.math.round(value * 1000) / 10
        return "${rounded}%"
    }
}
