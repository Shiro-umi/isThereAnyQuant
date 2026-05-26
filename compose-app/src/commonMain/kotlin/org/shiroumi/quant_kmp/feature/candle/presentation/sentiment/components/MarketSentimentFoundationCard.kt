package org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.candle.StrategySentimentResponse
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.ParameterSpec
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.ThresholdLine
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import org.shiroumi.quant_kmp.ui.theme.quantColors

@Composable
fun MarketSentimentFoundationCard(
    bullRatioSpec: ParameterSpec,
    ratioNormSpec: ParameterSpec,
    history: List<StrategySentimentResponse>,
    latest: StrategySentimentResponse?,
    cardHeight: androidx.compose.ui.unit.Dp,
    stacked: Boolean = false,
) {
    val bullRatioValue = latest?.let { bullRatioSpec.extractor(it) } ?: 0.0
    val ratioNormValue = latest?.let { ratioNormSpec.extractor(it) } ?: 0.0

    Card(
        modifier = Modifier.fillMaxWidth().height(cardHeight),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        if (stacked) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.lg)
            ) {
                FoundationMetricPanel(
                    title = "多头情绪广度",
                    narrative = bullRatioSpec.narrative(bullRatioValue),
                    formattedValue = bullRatioSpec.formatter(bullRatioValue),
                    history = history,
                    extractor = bullRatioSpec.extractor,
                    color = bullRatioSpec.color(),
                    yMin = bullRatioSpec.yMin,
                    yMax = bullRatioSpec.yMax,
                    unit = bullRatioSpec.unit,
                    thresholds = bullRatioSpec.thresholds,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                FoundationMetricPanel(
                    title = "趋势健康度",
                    narrative = ratioNormSpec.narrative(ratioNormValue),
                    formattedValue = ratioNormSpec.formatter(ratioNormValue),
                    history = history,
                    extractor = ratioNormSpec.extractor,
                    color = ratioNormSpec.color(),
                    yMin = ratioNormSpec.yMin,
                    yMax = ratioNormSpec.yMax,
                    unit = ratioNormSpec.unit,
                    thresholds = ratioNormSpec.thresholds,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.lg)
            ) {
                FoundationMetricPanel(
                    title = "多头情绪广度",
                    narrative = bullRatioSpec.narrative(bullRatioValue),
                    formattedValue = bullRatioSpec.formatter(bullRatioValue),
                    history = history,
                    extractor = bullRatioSpec.extractor,
                    color = bullRatioSpec.color(),
                    yMin = bullRatioSpec.yMin,
                    yMax = bullRatioSpec.yMax,
                    unit = bullRatioSpec.unit,
                    thresholds = bullRatioSpec.thresholds,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                FoundationMetricPanel(
                    title = "趋势健康度",
                    narrative = ratioNormSpec.narrative(ratioNormValue),
                    formattedValue = ratioNormSpec.formatter(ratioNormValue),
                    history = history,
                    extractor = ratioNormSpec.extractor,
                    color = ratioNormSpec.color(),
                    yMin = ratioNormSpec.yMin,
                    yMax = ratioNormSpec.yMax,
                    unit = ratioNormSpec.unit,
                    thresholds = ratioNormSpec.thresholds,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun FoundationMetricPanel(
    title: String,
    narrative: String,
    formattedValue: String,
    history: List<StrategySentimentResponse>,
    extractor: (StrategySentimentResponse) -> Double,
    color: Color,
    yMin: Double,
    yMax: Double,
    unit: String,
    thresholds: List<ThresholdLine>,
    modifier: Modifier = Modifier,
) {
    var isSimpleMode by remember { mutableStateOf(true) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = narrative,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                        lineHeight = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                modifier = Modifier.widthIn(min = 96.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { isSimpleMode = !isSimpleMode },
                    modifier = Modifier.size(28.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent
                    )
                ) {
                    Icon(
                        imageVector = if (isSimpleMode) Icons.Outlined.BarChart else Icons.AutoMirrored.Outlined.ShowChart,
                        contentDescription = if (isSimpleMode) "切换到详细" else "切换到简洁",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = formattedValue,
                    style = MaterialTheme.typography.displaySmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Crossfade(
            targetState = isSimpleMode,
            animationSpec = tween(400, easing = FastOutSlowInEasing),
            label = "foundation_chart_mode",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { simple ->
            if (simple) {
                MiniBarChart(
                    history = history,
                    extractor = extractor,
                    barColor = color,
                    yMin = yMin,
                    yMax = yMax,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                MiniLineChart(
                    history = history,
                    extractor = extractor,
                    lineColor = color,
                    yMin = yMin,
                    yMax = yMax,
                    unit = unit,
                    thresholds = thresholds,
                    withFill = true,
                    simpleMode = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun MiniBarChart(
    history: List<StrategySentimentResponse>,
    extractor: (StrategySentimentResponse) -> Double,
    barColor: Color,
    yMin: Double,
    yMax: Double,
    modifier: Modifier = Modifier,
    bars: Int = 18,
) {
    val values = remember(history, bars) {
        if (history.size <= bars) {
            history.map { extractor(it) }
        } else {
            val bucketSize = history.size / bars.toDouble()
            List(bars) { bucketIndex ->
                val start = (bucketIndex * bucketSize).toInt().coerceIn(0, history.lastIndex)
                val endExclusive = if (bucketIndex == bars - 1) {
                    history.size
                } else {
                    ((bucketIndex + 1) * bucketSize).toInt().coerceIn(start + 1, history.size)
                }
                val bucket = history.subList(start, endExclusive)
                if (bucketIndex == bars - 1) {
                    extractor(bucket.last())
                } else {
                    bucket.map(extractor).average()
                }
            }
        }
    }
    if (values.isEmpty()) return

    val range = (yMax - yMin).takeIf { it > 0.0 } ?: 1.0

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        values.forEachIndexed { index, value ->
            val fraction = ((value - yMin) / range).toFloat().coerceIn(0.05f, 1f)
            val isLast = index == values.lastIndex
            val color = if (isLast) barColor else barColor.copy(alpha = 0.45f)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                val spacerWeight = (1f - fraction).coerceAtLeast(0.001f)
                Spacer(modifier = Modifier.weight(spacerWeight))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(fraction)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(color)
                )
            }
        }
    }
}

@Composable
fun HealthStatusBadge(
    value: Double,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when {
        value >= 0.80 -> "OPTIMAL" to MaterialTheme.colorScheme.primary
        value >= 0.50 -> "HEALTHY" to MaterialTheme.colorScheme.tertiary
        value >= 0.20 -> "WEAK" to MaterialTheme.quantColors.warning
        else -> "CRITICAL" to MaterialTheme.colorScheme.error
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}
