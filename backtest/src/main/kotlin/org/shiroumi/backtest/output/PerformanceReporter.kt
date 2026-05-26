package org.shiroumi.backtest.output

import kotlinx.datetime.daysUntil
import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * 绩效指标计算器。
 */
class PerformanceReporter(private val riskFreeRateAnnual: Double = 0.0) {

    fun metrics(
        initialCapital: Money,
        equityCurve: List<EquityPoint>,
        fills: List<Fill>,
    ): PerformanceMetrics {
        if (equityCurve.isEmpty()) {
            return PerformanceMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val totalReturn = equityCurve.last().equity.toDouble() / initialCapital.toDouble() - 1.0
        val annualized = annualizedReturn(totalReturn, equityCurve.size)
        val dailyReturns = dailyReturns(initialCapital, equityCurve)
        val sharpe = sharpe(dailyReturns)
        val sortino = sortino(dailyReturns)
        val closedLots = lotContributions(fills)
        return PerformanceMetrics(
            totalReturn = roundTo(totalReturn, 6),
            annualizedReturn = roundTo(annualized, 6),
            maxDrawdown = roundTo(maxDrawdown(equityCurve), 6),
            sharpe = roundTo(sharpe, 6),
            sortino = roundTo(sortino, 6),
            winRate = roundTo(winRate(closedLots), 6),
            turnover = roundTo(turnover(fills, equityCurve), 6),
            avgHoldingDays = roundTo(avgHoldingDays(closedLots), 2),
        )
    }

    fun lotContributions(fills: List<Fill>): List<LotContribution> {
        val openLots = mutableMapOf<String, Fill>()
        val out = mutableListOf<LotContribution>()
        for (fill in fills.sortedWith(compareBy<Fill> { it.tradeDate }.thenBy { it.orderId })) {
            when (fill.side) {
                Side.BUY -> openLots[fill.tsCode] = fill
                Side.SELL -> {
                    val buy = openLots.remove(fill.tsCode) ?: continue
                    val buyCost = buy.grossAmount + buy.totalFee
                    val sellProceeds = fill.grossAmount - fill.totalFee
                    out += LotContribution(
                        tsCode = fill.tsCode,
                        buyDate = buy.tradeDate,
                        sellDate = fill.tradeDate,
                        holdingDays = buy.tradeDate.daysUntil(fill.tradeDate).coerceAtLeast(0),
                        pnl = sellProceeds - buyCost,
                        returnRate = sellProceeds.toDouble() / buyCost.toDouble() - 1.0,
                    )
                }
            }
        }
        return out
    }

    private fun annualizedReturn(totalReturn: Double, days: Int): Double {
        if (days <= 0) return 0.0
        return (1.0 + totalReturn).coerceAtLeast(0.0).pow(252.0 / days.toDouble()) - 1.0
    }

    private fun dailyReturns(initialCapital: Money, equityCurve: List<EquityPoint>): List<Double> {
        val out = mutableListOf<Double>()
        var previous = initialCapital.toDouble()
        for (point in equityCurve) {
            val current = point.equity.toDouble()
            if (previous > 0.0) out += current / previous - 1.0
            previous = current
        }
        return out
    }

    private fun maxDrawdown(equityCurve: List<EquityPoint>): Double {
        var peak = Double.NEGATIVE_INFINITY
        var maxDrawdown = 0.0
        for (point in equityCurve) {
            val equity = point.equity.toDouble()
            peak = maxOf(peak, equity)
            if (peak > 0.0) {
                maxDrawdown = maxOf(maxDrawdown, 1.0 - equity / peak)
            }
        }
        return maxDrawdown
    }

    private fun sharpe(returns: List<Double>): Double {
        if (returns.size < 2) return 0.0
        val excess = returns.map { it - riskFreeRateAnnual / 252.0 }
        val mean = excess.average()
        val std = sampleStd(excess)
        if (std == 0.0) return 0.0
        return mean / std * sqrt(252.0)
    }

    private fun sortino(returns: List<Double>): Double {
        if (returns.size < 2) return 0.0
        val threshold = riskFreeRateAnnual / 252.0
        val downside = returns.map { minOf(0.0, it - threshold) }
        val downsideStd = sqrt(downside.sumOf { it * it } / downside.size.toDouble())
        if (downsideStd == 0.0) return 0.0
        return (returns.average() - threshold) / downsideStd * sqrt(252.0)
    }

    private fun sampleStd(values: List<Double>): Double {
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1).toDouble()
        return sqrt(variance)
    }

    private fun winRate(closedLots: List<LotContribution>): Double {
        if (closedLots.isEmpty()) return 0.0
        return closedLots.count { it.pnl > Money.ZERO }.toDouble() / closedLots.size.toDouble()
    }

    private fun turnover(fills: List<Fill>, equityCurve: List<EquityPoint>): Double {
        val denominator = equityCurve.map { it.equity.toDouble() }.average()
        if (denominator <= 0.0) return 0.0
        val traded = fills.sumOf { it.grossAmount.toDouble() }
        return traded / denominator
    }

    private fun avgHoldingDays(closedLots: List<LotContribution>): Double {
        if (closedLots.isEmpty()) return 0.0
        return closedLots.map { it.holdingDays }.average()
    }

    private fun roundTo(value: Double, digits: Int): Double {
        val scale = 10.0.pow(digits)
        return round(value * scale) / scale
    }
}

/**
 * 一段完整的「建仓 → 清仓」生命周期的贡献明细。
 *
 * 作为 [SimulationResult.lotContributions] 暴露给可视化层，
 * 用于单票贡献排序、持仓时长分布、胜负样本统计等视图。
 */
@kotlinx.serialization.Serializable
data class LotContribution(
    val tsCode: String,
    val buyDate: kotlinx.datetime.LocalDate,
    val sellDate: kotlinx.datetime.LocalDate,
    val holdingDays: Int,
    val pnl: Money,
    val returnRate: Double,
)
