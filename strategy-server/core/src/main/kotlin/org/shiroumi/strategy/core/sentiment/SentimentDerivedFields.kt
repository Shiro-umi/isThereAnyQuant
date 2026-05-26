package org.shiroumi.strategy.core.sentiment

import kotlin.math.exp
import kotlin.math.max

/**
 * 情绪派生字段共享数学。
 *
 * 这些公式同时被盘后/盘中情绪计算器和数据库读取层使用。
 * 集中在这里避免常量与公式漂移。
 */
object SentimentMath {
    const val RATIO_LOW: Double = 0.206
    const val RATIO_HIGH: Double = 0.536
    const val ABSOLUTE_FLOOR: Double = 0.256
    const val VOL_CAP_THRESH: Double = 2.0
    const val VOL_CAP_MAX: Double = 0.6

    fun normalizeBullRatio(ratio: Double): Double =
        ((ratio - RATIO_LOW) / (RATIO_HIGH - RATIO_LOW)).coerceIn(0.0, 1.0)

    fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    fun absoluteFloorOf(bullRatio: Double): Double =
        if (bullRatio >= ABSOLUTE_FLOOR) 1.0 else 0.0

    fun calculateVolCap(
        volZ: Double,
        threshold: Double = VOL_CAP_THRESH,
        max: Double = VOL_CAP_MAX,
    ): Double {
        val excess = max(0.0, volZ - threshold)
        return max + (1.0 - max) / (1.0 + 2.0 * excess)
    }
}

/**
 * 情绪快照中由 bullRatio / volZ / accelZ 派生的五个字段。
 *
 * 写入时由计算器一次性产出；读取时如果遇到旧数据缺省（全 0 + 有效快照），
 * 通过 [restoreSentimentDerivedFields] 按相同公式回填。
 */
data class SentimentDerivedFields(
    val ratioNorm: Double,
    val volScore: Double,
    val accelScore: Double,
    val absoluteFloor: Double,
    val volCap: Double,
) {
    fun isAllZero(): Boolean =
        ratioNorm == 0.0 &&
            volScore == 0.0 &&
            accelScore == 0.0 &&
            absoluteFloor == 0.0 &&
            volCap == 0.0

    companion object {
        val ZERO: SentimentDerivedFields = SentimentDerivedFields(
            ratioNorm = 0.0,
            volScore = 0.0,
            accelScore = 0.0,
            absoluteFloor = 0.0,
            volCap = 0.0,
        )
    }
}

/**
 * 失败快照（`sufficientHistory = false` 或 `reason != null`）派生字段保持全 0,
 * 与写入时的占位语义一致;有效快照按公式还原。
 */
fun restoreSentimentDerivedFields(
    bullRatio: Double,
    volZ: Double,
    accelZ: Double,
    sufficientHistory: Boolean,
    reason: String?,
): SentimentDerivedFields {
    if (!sufficientHistory || reason != null) {
        return SentimentDerivedFields.ZERO
    }
    return SentimentDerivedFields(
        ratioNorm = SentimentMath.normalizeBullRatio(bullRatio),
        volScore = SentimentMath.sigmoid(-volZ),
        accelScore = SentimentMath.sigmoid(2.0 * accelZ),
        absoluteFloor = SentimentMath.absoluteFloorOf(bullRatio),
        volCap = SentimentMath.calculateVolCap(volZ),
    )
}
