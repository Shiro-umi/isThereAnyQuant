package org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import model.candle.StrategySentimentResponse
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.ThresholdLine
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.formatDouble

@Composable
fun MiniLineChart(
    history: List<StrategySentimentResponse>,
    extractor: (StrategySentimentResponse) -> Double,
    lineColor: Color,
    yMin: Double,
    yMax: Double,
    unit: String,
    thresholds: List<ThresholdLine>,
    modifier: Modifier = Modifier,
    withFill: Boolean = false,
    simpleMode: Boolean = false,
) {
    if (history.isEmpty()) return

    val colorOutline = MaterialTheme.colorScheme.outlineVariant
    val colorOnSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val fontSize = MaterialTheme.typography.labelSmall.fontSize

    var hoverIndex by remember { mutableStateOf<Int?>(null) }

    val resolvedThresholds = thresholds.map {
        Triple(it.value, it.label, it.color())
    }

    val chartHistory = if (simpleMode && history.size > 16) {
        val count = 16
        val step = (history.size - 1) / (count - 1).toDouble()
        (0 until count).map { i -> history[(i * step).toInt().coerceIn(history.indices)] }
    } else history

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pos = event.changes.firstOrNull()?.position
                            if (pos != null) {
                                val leftPad = if (simpleMode) 0f else 42f
                                val rightPad = if (simpleMode) 0f else 6f
                                val drawW = size.width - leftPad - rightPad
                                val stepX = if (chartHistory.size > 1) drawW / (chartHistory.size - 1).toFloat() else 0f
                                val idx = ((pos.x - leftPad) / stepX).toInt().coerceIn(0, chartHistory.lastIndex)
                                hoverIndex = idx
                            } else {
                                hoverIndex = null
                            }
                        }
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val leftPadding = if (simpleMode) 0f else 42f
            val rightPadding = if (simpleMode) 0f else 6f
            val topPadding = if (simpleMode) 12f else 6f
            val bottomPadding = if (simpleMode) 24f else 20f
            val drawWidth = width - leftPadding - rightPadding
            val drawHeight = height - topPadding - bottomPadding
            val range = yMax - yMin

            if (!simpleMode) {
                val gridLines = 3
                for (i in 0..gridLines) {
                    val fraction = i.toDouble() / gridLines
                    val yVal = yMin + range * fraction
                    val y = topPadding + drawHeight - (fraction * drawHeight).toFloat()

                    drawLine(
                        color = colorOutline.copy(alpha = 0.4f),
                        start = Offset(leftPadding, y),
                        end = Offset(leftPadding + drawWidth, y),
                        strokeWidth = 0.5f,
                    )

                    val label = when (unit) {
                        "%" -> "${formatDouble(yVal * 100, 0)}%"
                        "σ" -> "${formatDouble(yVal, 1)}σ"
                        "只" -> "${yVal.toInt()}"
                        else -> formatDouble(yVal, 2)
                    }
                    val textLayout = textMeasurer.measure(
                        text = label,
                        style = TextStyle(fontSize = fontSize, color = colorOnSurface.copy(alpha = 0.74f)),
                    )
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset(leftPadding - textLayout.size.width - 4f, y - textLayout.size.height / 2f),
                    )
                }

                for ((value, _, tColor) in resolvedThresholds) {
                    val fraction = ((value - yMin) / range).coerceIn(0.0, 1.0)
                    val y = topPadding + drawHeight - (fraction * drawHeight).toFloat()
                    drawLine(
                        color = tColor.copy(alpha = 0.5f),
                        start = Offset(leftPadding, y),
                        end = Offset(leftPadding + drawWidth, y),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                    )
                }
            }

            val stepX = if (chartHistory.size > 1) drawWidth / (chartHistory.size - 1).toFloat() else 0f
            val points = chartHistory.mapIndexed { index, item ->
                val value = extractor(item)
                val fraction = ((value - yMin) / range).coerceIn(0.0, 1.0)
                val x = leftPadding + index * stepX
                val y = topPadding + drawHeight - (fraction * drawHeight).toFloat()
                Offset(x, y)
            }

            if (withFill && points.isNotEmpty()) {
                val fillPath = Path()
                fillPath.moveTo(points.first().x, points.first().y)
                if (simpleMode && points.size > 1) {
                    fillPath.cubicSplineTo(points)
                } else {
                    for (i in 1 until points.size) fillPath.lineTo(points[i].x, points[i].y)
                }
                fillPath.lineTo(points.last().x, topPadding + drawHeight)
                fillPath.lineTo(points.first().x, topPadding + drawHeight)
                fillPath.close()
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = 0.22f), Color.Transparent),
                        startY = topPadding,
                        endY = topPadding + drawHeight,
                    ),
                )
            }

            val linePath = Path()
            if (simpleMode && points.size > 1) {
                linePath.moveTo(points.first().x, points.first().y)
                linePath.cubicSplineTo(points)
            } else {
                points.forEachIndexed { index, pt ->
                    if (index == 0) linePath.moveTo(pt.x, pt.y) else linePath.lineTo(pt.x, pt.y)
                }
            }
            val strokeWidth = if (simpleMode) 2.2f else 1.8f
            drawPath(path = linePath, color = lineColor, style = Stroke(width = strokeWidth))

            if (history.isNotEmpty()) {
                val dateIndices = if (simpleMode) {
                    listOf(0, history.lastIndex / 2, history.lastIndex)
                } else {
                    listOf(0, history.lastIndex)
                }
                for (idx in dateIndices.distinct()) {
                    val dateText = history[idx].tradeDate.let {
                        if (it.length >= 10) it.substring(5) else it
                    }
                    val alpha = if (simpleMode) 0.6f else 0.45f
                    val textLayout = textMeasurer.measure(
                        text = dateText,
                        style = TextStyle(fontSize = fontSize, color = colorOnSurface.copy(alpha = alpha)),
                    )
                    val xPos = when {
                        simpleMode && idx == 0 -> leftPadding
                        simpleMode && idx == history.lastIndex -> (leftPadding + drawWidth - textLayout.size.width).coerceAtLeast(leftPadding)
                        simpleMode -> (leftPadding + drawWidth / 2f - textLayout.size.width / 2f).coerceAtLeast(leftPadding)
                        idx == 0 -> leftPadding
                        else -> (leftPadding + drawWidth - textLayout.size.width).coerceAtLeast(leftPadding)
                    }
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset(xPos, topPadding + drawHeight + 4f),
                    )
                }
            }

            hoverIndex?.let { idx ->
                if (idx < points.size) {
                    val x = points[idx].x
                    val y = points[idx].y

                    if (!simpleMode) {
                        drawLine(
                            color = colorOnSurface.copy(alpha = 0.2f),
                            start = Offset(x, topPadding),
                            end = Offset(x, topPadding + drawHeight),
                            strokeWidth = 1f,
                        )
                    }
                    drawCircle(color = lineColor, radius = if (simpleMode) 4f else 3.5f, center = Offset(x, y))
                    drawCircle(color = lineColor.copy(alpha = 0.3f), radius = if (simpleMode) 8f else 7f, center = Offset(x, y))

                    if (!simpleMode) {
                        val value = extractor(chartHistory[idx])
                        val valueLabel = when (unit) {
                            "%" -> "${formatDouble(value * 100, 1)}%"
                            "σ" -> "${formatDouble(value, 2)}σ"
                            "只" -> "${value.toInt()}"
                            else -> formatDouble(value, 3)
                        }
                        val dateLabel = chartHistory[idx].tradeDate.let {
                            if (it.length >= 10) it.substring(5) else it
                        }
                        val hoverText = "$dateLabel  $valueLabel"
                        val hoverLayout = textMeasurer.measure(
                            text = hoverText,
                            style = TextStyle(fontSize = fontSize, color = lineColor, fontWeight = FontWeight.Bold),
                        )
                        val hoverX = (x - hoverLayout.size.width / 2f).coerceIn(leftPadding, leftPadding + drawWidth - hoverLayout.size.width)
                        val hoverY = (y - hoverLayout.size.height - 10f).coerceAtLeast(topPadding)

                        drawRoundRect(
                            color = colorOnSurface.copy(alpha = 0.1f),
                            topLeft = Offset(hoverX - 6f, hoverY - 3f),
                            size = Size(hoverLayout.size.width + 12f, hoverLayout.size.height + 6f),
                            cornerRadius = CornerRadius(4f),
                        )
                        drawText(textLayoutResult = hoverLayout, topLeft = Offset(hoverX, hoverY))
                    }
                }
            }
        }
    }
}

fun Path.cubicSplineTo(points: List<Offset>, tension: Float = 0.1667f) {
    if (points.size < 2) return
    for (i in 0 until points.size - 1) {
        val p1 = points[i]
        val p2 = points[i + 1]
        val p0 = points.getOrNull(i - 1)
            ?: Offset(2 * p1.x - p2.x, 2 * p1.y - p2.y)
        val p3 = points.getOrNull(i + 2)
            ?: Offset(2 * p2.x - p1.x, 2 * p2.y - p1.y)

        val cp1 = Offset(
            x = p1.x + (p2.x - p0.x) * tension,
            y = p1.y + (p2.y - p0.y) * tension
        )
        val cp2 = Offset(
            x = p2.x - (p3.x - p1.x) * tension,
            y = p2.y - (p3.y - p1.y) * tension
        )
        cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p2.x, p2.y)
    }
}
