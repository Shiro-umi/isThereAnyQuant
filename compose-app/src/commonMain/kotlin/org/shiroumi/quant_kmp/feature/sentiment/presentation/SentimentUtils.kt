package org.shiroumi.quant_kmp.feature.sentiment.presentation

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round

// multiplatform-safe 数值格式化
fun formatDouble(value: Double, decimals: Int): String {
    val factor = when (decimals) {
        0 -> 1.0
        1 -> 10.0
        2 -> 100.0
        3 -> 1000.0
        else -> 1.0
    }
    val rounded = round(value * factor) / factor
    val intPart = rounded.toLong()
    val fracPart = abs((rounded - intPart) * factor).toLong()
    return if (decimals == 0) "$intPart" else "$intPart.${fracPart.toString().padStart(decimals, '0')}"
}

fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
