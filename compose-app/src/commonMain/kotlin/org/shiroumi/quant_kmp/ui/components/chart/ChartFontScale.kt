package org.shiroumi.quant_kmp.ui.components.chart

/**
 * 图表字号平台补偿量（sp）
 * JS 平台 canvas 像素密度更高，需额外放大以保证可读性。
 */
internal expect val chartFontAddition: Int
