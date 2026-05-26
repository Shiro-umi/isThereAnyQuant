package org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WaterfallChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.candle.StrategySentimentResponse
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.ParameterSpec
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import org.shiroumi.quant_kmp.ui.theme.quantColors

@Composable
fun MomentumMetricsCard(
    specs: List<ParameterSpec>,
    latest: StrategySentimentResponse?,
    cardHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
    stackDetailPanels: Boolean = false,
) {
    val accelZSpec = specs.first { it.id == "accel_z" }
    val accelScoreSpec = specs.first { it.id == "accel_score" }
    val accelZ = latest?.accelZ ?: 0.0
    val accelScore = latest?.accelScore ?: 0.0
    val contentPadding = if (compactLayout) 20.dp else 24.dp
    val contentSpacing = if (compactLayout) 16.dp else 20.dp

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.TrendingUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "情绪动能",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = momentumInsight(accelZ = accelZ, accelScore = accelScore),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                maxLines = if (compactLayout) 5 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )

            if (compactLayout) {
                MomentumHeroPanel(
                    accelZSpec = accelZSpec,
                    accelScoreSpec = accelScoreSpec,
                    accelZ = accelZ,
                    accelScore = accelScore,
                    compactLayout = true,
                    stacked = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MomentumHeroPanel(
                        accelZSpec = accelZSpec,
                        accelScoreSpec = accelScoreSpec,
                        accelZ = accelZ,
                        accelScore = accelScore,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val detailModifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                    if (stackDetailPanels) {
                        Column(
                            modifier = detailModifier,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            MomentumZPanel(
                                spec = accelZSpec,
                                value = accelZ,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                            MomentumScorePanel(
                                spec = accelScoreSpec,
                                value = accelScore,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                        }
                    } else {
                        Row(
                            modifier = detailModifier,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            MomentumZPanel(
                                spec = accelZSpec,
                                value = accelZ,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                            MomentumScorePanel(
                                spec = accelScoreSpec,
                                value = accelScore,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VolatilityMetricsCard(
    specs: List<ParameterSpec>,
    latest: StrategySentimentResponse?,
    cardHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    compactLayout: Boolean = false,
) {
    if (stacked) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .height(cardHeight),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            specs.forEach { spec ->
                val value = latest?.let { spec.extractor(it) } ?: 0.0
                VolatilityRingMetric(
                    spec = spec,
                    value = value,
                    compactLayout = compactLayout,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(cardHeight),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            specs.forEach { spec ->
                val value = latest?.let { spec.extractor(it) } ?: 0.0
                VolatilityRingMetric(
                    spec = spec,
                    value = value,
                    compactLayout = compactLayout,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun GroupedMetricsCard(
    title: String,
    icon: @Composable () -> Unit,
    specs: List<ParameterSpec>,
    latest: StrategySentimentResponse?,
    cardHeight: androidx.compose.ui.unit.Dp,
    sectionSpacing: androidx.compose.ui.unit.Dp = 16.dp,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(cardHeight),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                icon()
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing)
            ) {
                specs.forEachIndexed { index, spec ->
                    val value = latest?.let { spec.extractor(it) } ?: 0.0
                    MetricNarrativeItem(
                        spec = spec,
                        value = value,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (index != specs.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VolatilityRingMetric(
    spec: ParameterSpec,
    value: Double,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
) {
    val tone = spec.color()
    val progress = ((value - spec.yMin) / (spec.yMax - spec.yMin)).coerceIn(0.0, 1.0).toFloat()

    val metricModifier = modifier
        .clip(RoundedCornerShape(AgentTheme.Shapes.medium))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)

    if (compactLayout) {
        Column(
            modifier = metricModifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RingMeter(
                progress = progress,
                color = tone,
                label = spec.formatter(value),
                modifier = Modifier.size(112.dp)
            )

            Text(
                text = spec.title,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = spec.narrative(value),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        Row(
            modifier = metricModifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RingMeter(
                progress = progress,
                color = tone,
                label = spec.formatter(value),
                modifier = Modifier.size(138.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = spec.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = spec.narrative(value),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RingMeter(
    progress: Float,
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.38f)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f
            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
            drawArc(
                color = color,
                startAngle = 135f,
                sweepAngle = 270f * progress.coerceIn(0.0f, 1.0f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

fun momentumInsight(
    accelZ: Double,
    accelScore: Double,
): String {
    return when {
        accelZ >= 1.0 && accelScore >= 0.70 ->
            "情绪修复速度和实际动能同步走强，说明市场不只是短暂反弹，而是正在形成更稳定的扩张节奏，可把它理解成“上涨斜率”和“仓位转化率”同时改善。"
        accelZ >= 0.0 && accelScore >= 0.70 ->
            "动能得分已经进入强势区，但加速度没有继续陡升，说明上涨更偏稳态推进，适合顺势跟随而不是激进追高。"
        accelZ < 0.0 && accelScore >= 0.70 ->
            "当前动能水平仍高，但边际速度开始回落，说明市场惯性仍在，只是上冲效率正在下降，需要警惕由强转缓。"
        accelZ >= 0.0 && accelScore >= 0.40 ->
            "情绪动能处于温和修复区，市场有继续回暖的基础，但还没进入高确定性的加速扩张阶段，更适合观察确认而非满仓押注。"
        accelZ < 0.0 && accelScore >= 0.40 ->
            "动能还没有完全转弱，但加速度已先行掉头，这通常意味着情绪修复开始放缓，后续更容易进入震荡整理。"
        else ->
            "情绪加速度和动能得分都偏弱，说明市场缺少持续抬升风险偏好的驱动力，当前更像弱势修复或下行过程中的短暂喘息。"
    }
}

@Composable
private fun MomentumHeroPanel(
    accelZSpec: ParameterSpec,
    accelScoreSpec: ParameterSpec,
    accelZ: Double,
    accelScore: Double,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
    stacked: Boolean = false,
) {
    if (stacked) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MomentumHeadlineMetric(
                title = accelZSpec.title,
                value = accelZSpec.formatter(accelZ),
                tone = accelZSpec.color(),
                label = momentumBucketLabel(accelZ, accelScore = null),
                compactLayout = compactLayout,
                modifier = Modifier.fillMaxWidth()
            )
            MomentumHeadlineMetric(
                title = accelScoreSpec.title,
                value = accelScoreSpec.formatter(accelScore),
                tone = accelScoreSpec.color(),
                label = momentumBucketLabel(accelZ = null, accelScore = accelScore),
                compactLayout = compactLayout,
                modifier = Modifier.fillMaxWidth()
            )
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MomentumHeadlineMetric(
                title = accelZSpec.title,
                value = accelZSpec.formatter(accelZ),
                tone = accelZSpec.color(),
                label = momentumBucketLabel(accelZ, accelScore = null),
                modifier = Modifier.weight(1f)
            )
            MomentumHeadlineMetric(
                title = accelScoreSpec.title,
                value = accelScoreSpec.formatter(accelScore),
                tone = accelScoreSpec.color(),
                label = momentumBucketLabel(accelZ = null, accelScore = accelScore),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MomentumHeadlineMetric(
    title: String,
    value: String,
    tone: Color,
    label: String,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AgentTheme.Shapes.medium))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.38f))
            .padding(if (compactLayout) 14.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactLayout) 6.dp else 10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = if (compactLayout) {
                MaterialTheme.typography.headlineMedium.copy(letterSpacing = 0.sp)
            } else {
                MaterialTheme.typography.displaySmall.copy(letterSpacing = 0.sp)
            },
            color = tone,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MomentumZPanel(
    spec: ParameterSpec,
    value: Double,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AgentTheme.Shapes.medium))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.28f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = spec.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = spec.narrative(value),
            style = MaterialTheme.typography.bodySmall.copy(
                fontStyle = FontStyle.Italic,
                lineHeight = 18.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        MomentumRangeRail(
            value = value,
            min = spec.yMin,
            max = spec.yMax,
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("-1σ", "0σ", "+1σ").forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MomentumScorePanel(
    spec: ParameterSpec,
    value: Double,
    modifier: Modifier = Modifier,
) {
    val progress = ((value - spec.yMin) / (spec.yMax - spec.yMin)).coerceIn(0.0, 1.0).toFloat()
    val tone = spec.color()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MomentumDescriptionBlock(
                title = "速度解读",
                body = speedDescription(value),
                tone = tone
            )
            MomentumDescriptionBlock(
                title = "仓位含义",
                body = scoreDescription(value, progress),
                tone = tone.copy(alpha = 0.82f)
            )
        }
    }
}

@Composable
private fun MomentumDescriptionBlock(
    title: String,
    body: String,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = tone,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MomentumRangeRail(
    value: Double,
    min: Double,
    max: Double,
    modifier: Modifier = Modifier,
) {
    val clamped = value.coerceIn(min, max)
    val marker = ((clamped - min) / (max - min)).toFloat()
    val zoneColors = listOf(
        MaterialTheme.quantColors.warning.copy(alpha = 0.72f),
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    )
    val markerOuter = MaterialTheme.colorScheme.surfaceBright
    val markerInner = MaterialTheme.colorScheme.onSurface
    val markerLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    Canvas(modifier = modifier) {
        val railHeight = 16.dp.toPx()
        val railTop = size.height / 2f - railHeight / 2f + 8.dp.toPx()
        val segmentWidth = size.width / 3f

        zoneColors.forEachIndexed { index, color ->
            drawRoundRect(
                color = color,
                topLeft = Offset(x = index * segmentWidth, y = railTop),
                size = Size(width = segmentWidth - 6.dp.toPx(), height = railHeight),
                cornerRadius = CornerRadius(railHeight / 2f, railHeight / 2f)
            )
        }

        val x = marker * size.width
        drawCircle(
            color = markerOuter,
            radius = 8.dp.toPx(),
            center = Offset(x, railTop - 10.dp.toPx())
        )
        drawCircle(
            color = markerInner,
            radius = 4.dp.toPx(),
            center = Offset(x, railTop - 10.dp.toPx())
        )
        drawLine(
            color = markerLine,
            start = Offset(x, railTop - 2.dp.toPx()),
            end = Offset(x, railTop + railHeight),
            strokeWidth = 2.dp.toPx()
        )
    }
}

private fun momentumBucketLabel(
    accelZ: Double? = null,
    accelScore: Double? = null,
): String = when {
    accelZ != null -> when {
        accelZ >= 1.0 -> "快速升温"
        accelZ >= 0.0 -> "温和抬升"
        accelZ >= -1.0 -> "边际走弱"
        else -> "快速降温"
    }
    accelScore != null -> when {
        accelScore >= 0.70 -> "趋势强化"
        accelScore >= 0.40 -> "修复延续"
        else -> "动力不足"
    }
    else -> ""
}

private fun speedDescription(value: Double): String = when {
    value >= 1.0 -> "情绪斜率正在快速抬升，市场愿意主动提高风险偏好，短期更容易出现强趋势推进。"
    value >= 0.0 -> "情绪仍在改善，但推进速度偏平缓，说明修复更像稳步爬升而不是爆发式扩张。"
    value >= -1.0 -> "情绪速度开始放缓，市场会从进攻切回观察，后续更容易进入震荡确认阶段。"
    else -> "情绪修复速度显著转负，市场风险偏好正在快速收缩，短期回撤压力会明显抬升。"
}

private fun scoreDescription(value: Double, progress: Float): String = when {
    value >= 0.70 -> "当前动能已经足够强，系统会把这类信号理解成可转化为有效仓位优势的趋势环境。"
    value >= 0.40 -> "动能仍有正向支撑，但还没强到形成压倒性优势，仓位表达更适合偏谨慎的顺势跟随。"
    progress > 0.12f -> "动能得分偏弱，情绪变化还没有稳定转化成趋势优势，贸然提高仓位的性价比不高。"
    else -> "当前动能非常有限，市场即便有短暂修复，也更像噪音反弹而不是可持续的主升推动。"
}

@Composable
private fun MetricNarrativeItem(
    spec: ParameterSpec,
    value: Double,
    modifier: Modifier = Modifier,
) {
    val tone = spec.color()
    val narrative = spec.narrative(value)
    val progress = ((value - spec.yMin) / (spec.yMax - spec.yMin)).coerceIn(0.0, 1.0).toFloat()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = spec.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (narrative.isNotBlank()) {
                    Text(
                        text = narrative,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontStyle = FontStyle.Italic,
                            lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = spec.formatter(value),
                style = MaterialTheme.typography.headlineSmall,
                color = tone,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        SegmentedProgressBar(
            progress = progress,
            color = tone,
            segments = 12,
            modifier = Modifier.fillMaxWidth()
        )

        if (spec.thresholds.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                spec.thresholds.take(2).forEach { threshold ->
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(threshold.color())
                        )
                        Text(
                            text = threshold.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
