package org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.candle.StrategySentimentResponse
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.ParameterSpec
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme

@Composable
fun SecondaryMetricsCard(
    specs: List<ParameterSpec>,
    latest: StrategySentimentResponse?,
    cardHeight: androidx.compose.ui.unit.Dp,
) {
    val fft = latest?.fftScore ?: 0.0
    val residual = latest?.residualScore ?: 0.0
    val floor = latest?.absoluteFloor ?: 0.0

    Card(
        modifier = Modifier.fillMaxWidth().height(cardHeight),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.BarChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "情绪因子拆解",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                specs.forEach { spec ->
                    val value = latest?.let { spec.extractor(it) } ?: 0.0
                    MetricBarItem(
                        spec = spec,
                        value = value,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            FactorInsightPanel(
                insight = factorInsight(fft = fft, residual = residual, floor = floor),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

fun factorInsight(fft: Double, residual: Double, floor: Double): String {
    return when {
        floor < 0.5 -> "绝对水位保护已触发，当前不满足情绪安全条件，但模型 Top5 仍会展示。"
        residual >= 0.70 && fft >= 0.70 -> "周期底部与超卖信号共振，情绪拐点向上概率较高，可积极布局。"
        residual >= 0.70 && fft < 0.40 -> "市场虽处周期顶部回落段，但残差提示明显超卖，存在左侧博弈机会。"
        residual < 0.40 && fft >= 0.70 -> "周期虽处回升段，但残差显示价格已高于预期，追高需谨慎。"
        residual < 0.40 && fft < 0.40 -> "周期顶部与过热残差双重偏空，情绪面临较大下行风险，建议降仓。"
        fft >= 0.70 -> "FFT相位处于底部回升，情绪周期有望改善。"
        fft < 0.40 -> "FFT相位处于顶部回落，情绪周期面临压力。"
        residual >= 0.70 -> "残差得分偏高，市场可能存在超卖机会。"
        residual < 0.40 -> "残差得分偏低，需警惕过热风险。"
        else -> "各情绪因子信号不一，建议维持中性仓位，等待方向明朗。"
    }
}

@Composable
fun FactorInsightPanel(
    insight: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AgentTheme.Shapes.medium))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = insight,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f)
        )
    }
}

@Composable
fun MetricBarItem(
    spec: ParameterSpec,
    value: Double,
    modifier: Modifier = Modifier,
) {
    val progress = ((value - spec.yMin) / (spec.yMax - spec.yMin)).coerceIn(0.0, 1.0).toFloat()
    val barColor = spec.color()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = spec.title.uppercase(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                letterSpacing = 0.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = spec.formatter(value),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 26.sp,
                    letterSpacing = 0.sp
                ),
                color = barColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        SegmentedProgressBar(
            progress = progress,
            color = barColor,
            segments = 12,
            modifier = Modifier.fillMaxWidth()
        )

        val narrativeText = spec.narrative(value)
        if (narrativeText.isNotBlank()) {
            Text(
                text = narrativeText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SegmentedProgressBar(
    progress: Float,
    color: Color,
    segments: Int,
    modifier: Modifier = Modifier,
) {
    val filledCount = (progress * segments).toInt().coerceIn(0, segments)
    Row(
        modifier = modifier.height(8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(segments) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index < filledCount) color.copy(alpha = 0.9f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)
                    )
            )
        }
    }
}
