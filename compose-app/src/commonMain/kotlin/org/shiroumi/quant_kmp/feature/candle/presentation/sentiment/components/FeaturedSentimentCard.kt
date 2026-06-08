package org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.candle.StrategySentimentResponse
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.ParameterSpec
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.formatDouble
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.theme.quantColors

@Composable
fun FeaturedSentimentCard(
    spec: ParameterSpec,
    history: List<StrategySentimentResponse>,
    latest: StrategySentimentResponse?,
    cardHeight: androidx.compose.ui.unit.Dp,
    compactLayout: Boolean = false,
) {
    val config = rememberAdaptiveLayoutConfig()
    val isCompact = compactLayout || config.isCompact
    val lineColor = spec.color()
    val latestValue = latest?.let { spec.extractor(it) } ?: 0.0
    val previousValue = history.dropLast(1).lastOrNull()?.let { spec.extractor(it) }
    val change = if (previousValue != null) latestValue - previousValue else 0.0
    val hasPrevious = previousValue != null

    val percentile = remember(history, latestValue) {
        if (history.isEmpty()) null
        else {
            val sorted = history.map { spec.extractor(it) }.sorted()
            val rank = sorted.indexOfLast { it <= latestValue } + 1
            rank.toDouble() / sorted.size
        }
    }
    val narrative = sentimentExposureNarrative(latestValue, percentile)

    val volCap = latest?.volCap ?: 0.0
    val marketVol = latest?.marketVol ?: 0.0
    var isSimpleMode by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth().height(cardHeight),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        if (isCompact) {
            FeaturedCompactContent(
                spec = spec,
                latestValue = latestValue,
                hasPrevious = hasPrevious,
                change = change,
                narrative = narrative,
                volCap = volCap,
                marketVol = marketVol,
                history = history,
                lineColor = lineColor,
                isSimpleMode = isSimpleMode,
                onToggleSimpleMode = { isSimpleMode = !isSimpleMode }
            )
        } else {
            FeaturedExpandedContent(
                spec = spec,
                latestValue = latestValue,
                hasPrevious = hasPrevious,
                change = change,
                narrative = narrative,
                volCap = volCap,
                marketVol = marketVol,
                history = history,
                lineColor = lineColor,
                isSimpleMode = isSimpleMode,
                onToggleSimpleMode = { isSimpleMode = !isSimpleMode }
            )
        }
    }
}

@Composable
private fun FeaturedCompactContent(
    spec: ParameterSpec,
    latestValue: Double,
    hasPrevious: Boolean,
    change: Double,
    narrative: String,
    volCap: Double,
    marketVol: Double,
    history: List<StrategySentimentResponse>,
    lineColor: Color,
    isSimpleMode: Boolean,
    onToggleSimpleMode: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = spec.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = onToggleSimpleMode,
                modifier = Modifier.size(28.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Transparent
                )
            ) {
                Icon(
                    imageVector = if (isSimpleMode) Icons.Outlined.BarChart else Icons.AutoMirrored.Outlined.ShowChart,
                    contentDescription = if (isSimpleMode) "切换到详细" else "切换到简洁",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ExposureValueRow(
            valueText = spec.formatter(latestValue),
            hasPrevious = hasPrevious,
            change = change,
            compact = true,
        )

        Text(
            text = narrative,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Crossfade(
                targetState = isSimpleMode,
                animationSpec = tween(400, easing = FastOutSlowInEasing),
                label = "chart_mode_compact",
                modifier = Modifier.fillMaxSize()
            ) { simple ->
                MiniLineChart(
                    history = history,
                    extractor = spec.extractor,
                    lineColor = lineColor,
                    yMin = spec.yMin,
                    yMax = spec.yMax,
                    unit = spec.unit,
                    thresholds = spec.thresholds,
                    withFill = true,
                    simpleMode = simple,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricPill(
                label = "情绪上限",
                value = "${formatDouble(volCap * 100, 1)}%"
            )
            MetricPill(
                label = "全市场波动率",
                value = "${formatDouble(marketVol * 100, 2)}%"
            )
        }
    }
}

@Composable
private fun FeaturedExpandedContent(
    spec: ParameterSpec,
    latestValue: Double,
    hasPrevious: Boolean,
    change: Double,
    narrative: String,
    volCap: Double,
    marketVol: Double,
    history: List<StrategySentimentResponse>,
    lineColor: Color,
    isSimpleMode: Boolean,
    onToggleSimpleMode: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .width(expandedExposureInfoMinWidth())
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(
                        text = spec.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ExposureValueRow(
                        valueText = spec.formatter(latestValue),
                        hasPrevious = hasPrevious,
                        change = change,
                        compact = false,
                    )

                    Text(
                        text = narrative,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricPill(
                        label = "情绪上限",
                        value = "${formatDouble(volCap * 100, 1)}%"
                    )
                    MetricPill(
                        label = "全市场波动率",
                        value = "${formatDouble(marketVol * 100, 2)}%"
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                Crossfade(
                    targetState = isSimpleMode,
                    animationSpec = tween(400, easing = FastOutSlowInEasing),
                    label = "chart_mode",
                    modifier = Modifier.fillMaxSize()
                ) { simple ->
                    MiniLineChart(
                        history = history,
                        extractor = spec.extractor,
                        lineColor = lineColor,
                        yMin = spec.yMin,
                        yMax = spec.yMax,
                        unit = spec.unit,
                        thresholds = spec.thresholds,
                        withFill = true,
                        simpleMode = simple,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                IconButton(
                    onClick = onToggleSimpleMode,
                    modifier = Modifier.align(Alignment.TopEnd),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent
                    )
                ) {
                    Icon(
                        imageVector = if (isSimpleMode) Icons.Outlined.BarChart else Icons.AutoMirrored.Outlined.ShowChart,
                        contentDescription = if (isSimpleMode) "切换到详细" else "切换到简洁",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ExposureValueRow(
    valueText: String,
    hasPrevious: Boolean,
    change: Double,
    compact: Boolean,
) {
    val isUp = change >= 0
    val valueMinWidth = exposureValueMinWidth(compact)
    val changeMinWidth = exposureChangeMinWidth()

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = valueText,
            style = if (compact) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .widthIn(min = valueMinWidth)
                .alignByBaseline()
        )

        if (hasPrevious) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .widthIn(min = changeMinWidth)
                    .alignByBaseline()
            ) {
                Icon(
                    imageVector = if (isUp) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isUp) MaterialTheme.colorScheme.primary else MaterialTheme.quantColors.warning
                )
                Text(
                    text = "${if (isUp) "+" else ""}${formatDouble(change * 100, 1)}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isUp) MaterialTheme.colorScheme.primary else MaterialTheme.quantColors.warning,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

private fun exposureValueMinWidth(compact: Boolean) = if (compact) 128.dp else 168.dp

private fun exposureChangeMinWidth() = 84.dp

private fun expandedExposureInfoMinWidth() =
    exposureValueMinWidth(compact = false) + 8.dp + exposureChangeMinWidth()

fun sentimentExposureNarrative(value: Double, percentile: Double?): String {
    val rankPhrase = when {
        percentile == null -> ""
        percentile >= 0.95 -> "处于历史极高水平（前${((1 - percentile) * 100).toInt()}%）"
        percentile >= 0.80 -> "维持在历史高分位（前${((1 - percentile) * 100).toInt()}%）"
        percentile >= 0.60 -> "位于历史中上游"
        percentile >= 0.40 -> "位于历史中位数附近"
        percentile >= 0.20 -> "位于历史中下游"
        else -> "处于历史较低水平（后${(percentile * 100).toInt()}%）"
    }
    return when {
        value >= 0.85 -> "当前市场情绪处于强势扩张期，$rankPhrase，多头力量充沛。"
        value >= 0.70 -> "市场情绪积极，$rankPhrase，短期趋势向上。"
        value >= 0.55 -> "市场多空相对均衡，情绪略微偏暖，$rankPhrase。"
        value >= 0.40 -> "多空力量胶着，策略信号中性，$rankPhrase。"
        value >= 0.20 -> "市场情绪偏弱，赚钱效应不足，$rankPhrase。"
        value > 0.0 -> "当前空头氛围浓厚，策略处于防御状态，$rankPhrase。"
        else -> "绝对水位保护已触发，市场不满足情绪安全红线，但模型 Top5 仍会展示。"
    }
}

@Composable
fun MetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(AgentTheme.Shapes.small)
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
