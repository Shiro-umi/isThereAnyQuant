package org.shiroumi.strategy.core.sentiment

/**
 * 情绪运行时种子相关的数学工具。
 *
 * 这组函数只解决一个问题：
 * 如何把盘后连续滚动出来的情绪状态，无损地延续到下一交易日盘中。
 *
 * 其中最关键的是 `accelWindow`：
 * - 原公式先对 `bullRatio` 做差分
 * - 再对差分做 10 日 EMA
 * - 最后对 EMA 序列做 252 日 z-score
 *
 * 因此盘中恢复时，最小充分状态不是“最近 252 个 bullRatio”，
 * 而是“最近 252 个 accel EMA 值 + 上一个 bullRatio”。
 */
object SentimentRuntimeMath {
    const val RUNTIME_WINDOW = 252
    private const val ACCEL_EMA_SPAN = 10

    /**
     * 追加滑动窗口末尾值，并裁剪到固定长度。
     */
    fun appendRollingWindow(
        previousWindow: List<Double>,
        newValue: Double,
        limit: Int = RUNTIME_WINDOW
    ): List<Double> {
        if (limit <= 0) return emptyList()
        return (previousWindow + newValue).takeLast(limit)
    }

    /**
     * 从完整 bullRatio 历史精确重建 accel EMA 窗口。
     *
     * 这个方法用于首次 seed 生成或旧 seed 缺失时的回补路径。
     * 为了和连续滚动结果一致，输入必须是“从最早可用历史到当前”的完整序列，
     * 不能只传一个已经截断到 252 的局部窗口。
     */
    fun rebuildAccelWindowFromBullRatioHistory(
        bullRatioHistory: List<Double>,
        limit: Int = RUNTIME_WINDOW
    ): List<Double> {
        if (bullRatioHistory.isEmpty()) return emptyList()
        val diffSeries = DoubleArray(bullRatioHistory.size)
        for (index in bullRatioHistory.indices) {
            diffSeries[index] = if (index == 0) {
                0.0
            } else {
                bullRatioHistory[index] - bullRatioHistory[index - 1]
            }
        }

        val accelSeries = DoubleArray(diffSeries.size)
        var previousEma: Double? = null
        for (index in diffSeries.indices) {
            val current = updateEma(previousEma, diffSeries[index], ACCEL_EMA_SPAN)
            accelSeries[index] = current
            previousEma = current
        }
        return accelSeries.takeLast(limit)
    }

    /**
     * 基于上一交易日 seed 的连续状态，增量生成下一交易日的 accel 窗口。
     *
     * 这条路径是日常盘后生成 seed 的主路径，复杂度 O(1)。
     */
    fun appendAccelWindow(
        previousBullRatio: Double?,
        currentBullRatio: Double,
        previousAccelWindow: List<Double>,
        limit: Int = RUNTIME_WINDOW
    ): List<Double> {
        val diff = previousBullRatio?.let { currentBullRatio - it } ?: 0.0
        val previousAccelEma = previousAccelWindow.lastOrNull()
        val nextAccel = updateEma(previousAccelEma, diff, ACCEL_EMA_SPAN)
        return appendRollingWindow(previousAccelWindow, nextAccel, limit)
    }

    private fun updateEma(previousEma: Double?, value: Double, period: Int): Double {
        val multiplier = 2.0 / (period + 1)
        return previousEma?.let { (value - it) * multiplier + it } ?: value
    }
}
