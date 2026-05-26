package org.shiroumi.quant_kmp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.candle.CandleData
import model.strategy.StockChartData

/**
 * 股票K线图组件
 * 可复用的K线图展示组件，用于显示股票的历史K线数据
 *
 * @param chartData 股票图表数据
 * @param modifier 修饰符
 * @param showVolume 是否显示成交量图
 * @param showRsi 是否显示RSI图
 * @param chartHeight K线图高度
 */
@Composable
fun StockChartComponent(
    chartData: StockChartData,
    modifier: Modifier = Modifier,
    showVolume: Boolean = true,
    showRsi: Boolean = true,
    chartHeight: Int = 200
) {
    val candles = chartData.candles
    val ema20 = chartData.ema20
    val rsi = chartData.rsi
    if (candles.isEmpty()) return

    // 计算价格范围
    val allPrices = candles.flatMap { listOf(it.high, it.low) } +
            ema20.filterNotNull()
    val maxPrice = allPrices.maxOrNull() ?: 0f
    val minPrice = allPrices.minOrNull() ?: 0f
    val priceRange = maxPrice - minPrice

    // 计算成交量范围
    val maxVolume = candles.maxOfOrNull { it.volume } ?: 1f

    // 主题色 - 涨跌使用鲜明配色（中国股市传统：红涨绿跌）
    val colorScheme = MaterialTheme.colorScheme
    val upColor = colorScheme.primary
    val downColor = colorScheme.tertiary
    val emaColor = colorScheme.secondary
    val rsiColor = colorScheme.tertiary

    Column(modifier = modifier.fillMaxWidth()) {
        // 股票名称和代码
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${chartData.name} (${chartData.code})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "交易日: ${chartData.tradeDate}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // K线图 + EMA20
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight.dp)
        ) {
            val textMeasurer = rememberTextMeasurer()

            Canvas(modifier = Modifier.fillMaxSize()) {
                val chartWidth = size.width - 45f
                val chartHeightPx = size.height - 30f
                val candleWidth = chartWidth / candles.size * 0.65f
                val spacing = chartWidth / candles.size

                // 网格线
                this.drawGridLines(chartWidth, chartHeightPx)

                // K线
                candles.forEachIndexed { index, candle ->
                    val x = index.toFloat() * spacing + spacing / 2f
                    val openY = chartHeightPx - (candle.open - minPrice) / priceRange * chartHeightPx
                    val closeY = chartHeightPx - (candle.close - minPrice) / priceRange * chartHeightPx
                    val highY = chartHeightPx - (candle.high - minPrice) / priceRange * chartHeightPx
                    val lowY = chartHeightPx - (candle.low - minPrice) / priceRange * chartHeightPx

                    val isUp = candle.close >= candle.open
                    val color = if (isUp) upColor else downColor

                    val bodyTop = minOf(openY, closeY)
                    val bodyBottom = maxOf(openY, closeY)
                    val bodyHeight = maxOf(bodyBottom - bodyTop, 1f)

                    if (isUp) {
                        // 空心阳线
                        if (highY < bodyTop) {
                            drawLine(color, Offset(x, highY), Offset(x, bodyTop), strokeWidth = 1f)
                        }
                        if (lowY > bodyBottom) {
                            drawLine(color, Offset(x, bodyBottom), Offset(x, lowY), strokeWidth = 1f)
                        }
                        drawRect(
                            color = color,
                            topLeft = Offset(x - candleWidth / 2, bodyTop),
                            size = Size(candleWidth, bodyHeight),
                            style = Stroke(width = 1.5f)
                        )
                    } else {
                        // 实心阴线
                        if (highY < bodyTop) {
                            drawLine(color, Offset(x, highY), Offset(x, bodyTop), strokeWidth = 1f)
                        }
                        if (lowY > bodyBottom) {
                            drawLine(color, Offset(x, bodyBottom), Offset(x, lowY), strokeWidth = 1f)
                        }
                        drawRect(
                            color = color,
                            topLeft = Offset(x - candleWidth / 2, bodyTop),
                            size = Size(candleWidth, bodyHeight)
                        )
                    }
                }

                // EMA20线
                val emaPath = Path()
                var firstEma = true
                ema20.forEachIndexed { index, value ->
                    if (value != null) {
                        val x = index.toFloat() * spacing + spacing / 2f
                        val y = chartHeightPx - (value - minPrice) / priceRange * chartHeightPx
                        if (firstEma) {
                            emaPath.moveTo(x, y)
                            firstEma = false
                        } else {
                            emaPath.lineTo(x, y)
                        }
                    }
                }
                drawPath(emaPath, emaColor, style = Stroke(width = 1.2f))

                // 价格标签
                for (i in 0..4) {
                    val price = minPrice + priceRange * i / 4
                    val y = chartHeightPx - i * chartHeightPx / 4
                    drawText(
                        textMeasurer = textMeasurer,
                        text = price.format(2),
                        style = TextStyle(color = Color.Gray, fontSize = 9.sp),
                        topLeft = Offset(chartWidth + 5f, y - 5f)
                    )
                }
            }
        }

        if (showVolume) {
            Spacer(modifier = Modifier.height(12.dp))

            // 成交量图
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val chartWidth = size.width - 45f
                    val chartHeightPx = size.height
                    val spacing = chartWidth / candles.size
                    val barWidth = spacing * 0.65f

                    candles.forEachIndexed { index, candle ->
                        val x = index.toFloat() * spacing + spacing / 2f
                        val volHeight = candle.volume / maxVolume * chartHeightPx * 0.9f
                        val isUp = candle.close >= candle.open

                        drawRect(
                            color = if (isUp) upColor else downColor,
                            topLeft = Offset(x - barWidth / 2, chartHeightPx - volHeight),
                            size = Size(barWidth, volHeight),
                            alpha = 0.6f
                        )
                    }
                }
            }
        }

        if (showRsi) {
            Spacer(modifier = Modifier.height(12.dp))

            // RSI图
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
            ) {
                val textMeasurer = rememberTextMeasurer()

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val chartWidth = size.width - 45f
                    val chartHeightPx = size.height - 15f
                    val spacing = chartWidth / candles.size

                    val overboughtY = chartHeightPx * 0.3f
                    val oversoldY = chartHeightPx * 0.9f
                    val midY = chartHeightPx * 0.6f

                    // 参考线
                    drawLine(
                        Color.Gray.copy(alpha = 0.25f),
                        Offset(0f, overboughtY),
                        Offset(chartWidth, overboughtY),
                        strokeWidth = 1f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    )
                    drawLine(
                        Color.Gray.copy(alpha = 0.25f),
                        Offset(0f, midY),
                        Offset(chartWidth, midY),
                        strokeWidth = 1f
                    )
                    drawLine(
                        Color.Gray.copy(alpha = 0.25f),
                        Offset(0f, oversoldY),
                        Offset(chartWidth, oversoldY),
                        strokeWidth = 1f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    )

                    // RSI线
                    val rsiPath = Path()
                    var firstRsi = true
                    rsi.forEachIndexed { index, value ->
                        if (value != null) {
                            val x = index.toFloat() * spacing + spacing / 2f
                            val y = chartHeightPx - (value / 100f) * chartHeightPx
                            if (firstRsi) {
                                rsiPath.moveTo(x, y)
                                firstRsi = false
                            } else {
                                rsiPath.lineTo(x, y)
                            }
                        }
                    }
                    drawPath(rsiPath, rsiColor, style = Stroke(width = 1f))

                    // 标签
                    drawText(
                        textMeasurer,
                        "RSI",
                        style = TextStyle(color = rsiColor, fontSize = 9.sp),
                        topLeft = Offset(chartWidth + 5f, 0f)
                    )
                }
            }
        }

        // 图例
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).background(upColor, RoundedCornerShape(1.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Text("涨", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).background(downColor, androidx.compose.foundation.shape.RoundedCornerShape(1.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Text("跌", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(16.dp).height(2.dp).background(emaColor))
                Spacer(modifier = Modifier.width(4.dp))
                Text("EMA20", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showRsi) {
                Spacer(modifier = Modifier.width(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.width(16.dp).height(2.dp).background(rsiColor))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("RSI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 网格线
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGridLines(chartWidth: Float, chartHeightPx: Float) {
    val chartHeight = chartHeightPx
    val gridColor = Color.Gray.copy(alpha = 0.12f)
    for (i in 0..4) {
        val y = chartHeightPx * i / 4
        drawLine(gridColor, Offset(0f, y), Offset(chartWidth, y), strokeWidth = 1f)
    }
    for (i in 0..5) {
        val x = chartWidth * i / 5
        drawLine(gridColor, Offset(x, 0f), Offset(x, chartHeightPx), strokeWidth = 1f)
    }
}

/**
 * Float格式化
 */
private fun Float.format(decimals: Int): String {
    var multiplier = 1
    repeat(decimals) { multiplier *= 10 }
    val rounded = kotlin.math.round(this * multiplier) / multiplier
    return rounded.toString()
}
