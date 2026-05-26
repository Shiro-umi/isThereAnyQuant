package org.shiroumi.quant_kmp.ui.utils

import kotlin.math.abs
import kotlin.math.round

/**
 * 格式化价格（避免使用String.format，兼容JS平台）
 *
 * @param price 价格值
 * @return 格式化后的价格字符串，保留2位小数
 */
fun formatPriceRaw(price: Float): String {
    val rounded = round(price * 100) / 100
    val intPart = rounded.toInt()
    val decimal = abs((rounded - intPart) * 100).toInt()
    return "$intPart.${decimal.toString().padStart(2, '0')}"
}

/**
 * 格式化百分比（避免使用String.format，兼容JS平台）
 *
 * @param value 百分比值
 * @param showSign 是否显示正负号
 * @return 格式化后的百分比字符串，保留2位小数
 */
fun formatPercentRaw(value: Float, showSign: Boolean = false): String {
    val sign = if (showSign && value >= 0) "+" else ""
    val rounded = round(value * 100) / 100
    val intPart = rounded.toInt()
    val decimal = abs((rounded - intPart) * 100).toInt()
    return "$sign$intPart.${decimal.toString().padStart(2, '0')}"
}

/**
 * 格式化成交量（自动转换为亿/万）
 *
 * @param volume 成交量
 * @return 格式化后的成交量字符串
 */
fun formatVolumeRaw(volume: Float): String {
    return when {
        volume >= 100000000 -> {
            val yi = volume / 100000000
            val rounded = round(yi * 100) / 100
            "${rounded}亿手"
        }
        volume >= 10000 -> {
            val wan = volume / 10000
            val rounded = round(wan * 100) / 100
            "${rounded}万手"
        }
        else -> "${volume.toInt()}手"
    }
}

/**
 * 格式化金额（自动转换为亿/万）
 *
 * @param amount 金额
 * @return 格式化后的金额字符串
 */
fun formatAmountRaw(amount: Float): String {
    return when {
        amount >= 100000000 -> {
            val yi = amount / 100000000
            val rounded = round(yi * 100) / 100
            "${rounded}亿"
        }
        amount >= 10000 -> {
            val wan = amount / 10000
            val rounded = round(wan * 100) / 100
            "${rounded}万"
        }
        else -> formatPriceRaw(amount)
    }
}

/**
 * 格式化市值（自动转换为千亿/亿/万）
 *
 * @param cap 市值
 * @return 格式化后的市值字符串
 */
fun formatMarketCapRaw(cap: Float): String {
    return when {
        cap >= 100000000000 -> {
            val qianYi = cap / 100000000000
            val rounded = round(qianYi * 10) / 10
            "${rounded}千亿"
        }
        cap >= 100000000 -> {
            val yi = cap / 100000000
            val rounded = round(yi * 10) / 10
            "${rounded}亿"
        }
        else -> {
            val wan = cap / 10000
            "${wan.toInt()}万"
        }
    }
}

// ==================== Float扩展函数 ====================

/**
 * 格式化价格（Float扩展函数版本）
 */
fun Float.formatPrice(): String = formatPriceRaw(this)

/**
 * 格式化百分比（Float扩展函数版本）
 */
fun Float.formatPercent(showSign: Boolean = false): String = formatPercentRaw(this, showSign)

/**
 * 格式化成交量（Float扩展函数版本）
 */
fun Float.formatVolume(): String = formatVolumeRaw(this)

/**
 * 格式化金额（Float扩展函数版本）
 */
fun Float.formatAmount(): String = formatAmountRaw(this)

/**
 * 格式化市值（Float扩展函数版本）
 */
fun Float.formatMarketCap(): String = formatMarketCapRaw(this)
