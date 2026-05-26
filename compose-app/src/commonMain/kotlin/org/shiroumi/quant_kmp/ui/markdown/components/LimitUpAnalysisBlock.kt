package org.shiroumi.quant_kmp.ui.markdown.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.shiroumi.quant_kmp.ui.markdown.LimitUpBlockSpec
import org.shiroumi.quant_kmp.ui.theme.quantColors

/**
 * 涨停/封板分析自定义渲染块。
 *
 * 作为 Markdown 报告正文中的增强段落呈现，避免使用厚重卡片打断阅读节奏。
 */
@Composable
fun LimitUpAnalysisBlock(
    spec: LimitUpBlockSpec,
    modifier: Modifier = Modifier
) {
    val sealColor = sealQualityColor(spec.sealQuality, MaterialTheme.colorScheme.secondary)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "涨停分析",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = spec.tsCode,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BadgeLabel(
                    text = spec.sealQuality ?: "涨停",
                    backgroundColor = sealColor.copy(alpha = 0.16f),
                    textColor = sealColor
                )
                spec.consecutiveCount?.let { count ->
                    BadgeLabel(
                        text = "${count}连板",
                        backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                        textColor = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        Text(
            text = naturalizeReportText(spec.recentSummary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
        )

        SectionBlock(
            title = "主力行为分析",
            content = spec.institutionalAnalysis,
            indicatorColor = MaterialTheme.colorScheme.secondary
        )

        spec.conclusion?.let { conclusion ->
            EditorialCalloutBlock(
                text = conclusion
            )
        }
    }
}

/** 根据封板质量返回颜色，[defaultColor] 为未匹配时的回退色。 */
@Composable
private fun sealQualityColor(quality: String?, defaultColor: Color): Color {
    val q = quality ?: ""
    val semantic = MaterialTheme.quantColors
    return when {
        q.contains("强") -> semantic.bullish
        q.contains("弱") || q.contains("炸") -> semantic.warning
        else -> defaultColor
    }
}

/**
 * 分析章节：保留轻量标题层级，避免正文中出现卡片套卡片。
 */
@Composable
internal fun SectionBlock(
    title: String,
    content: String,
    indicatorColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = indicatorColor
        )
        Text(
            text = naturalizeReportText(content),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
        )
    }
}

/**
 * Markdown 报告中的 editorial callout。
 *
 * 比卡片更轻，靠左侧强调线建立层级，适合承载结论、辩证关系、风险提示。
 */
@Composable
internal fun EditorialCalloutBlock(
    text: String,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.tertiary,
    italic: Boolean = true
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
        shape = RoundedCornerShape(
            topStart = 0.dp,
            bottomStart = 0.dp,
            topEnd = 16.dp,
            bottomEnd = 16.dp
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
        tonalElevation = 1.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        color = accentColor,
                        topLeft = Offset.Zero,
                        size = Size(3.dp.toPx(), size.height),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
        ) {
            Text(
                text = naturalizeReportText(text),
                modifier = Modifier
                    .padding(start = 20.dp, top = 20.dp, end = 22.dp, bottom = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 展示层兜底：工具字段名是内部读数，不应该直接出现在分析报告里。
 * Prompt 会约束新报告自然语言化；这里主要保护历史报告和偶发漏网输出。
 */
internal fun naturalizeReportText(text: String): String {
    return text
        .replace("sentimentExposure", "市场参与意愿")
        .replace("bullRatio", "看涨扩散度")
        .replace("marketVol", "盘面波动水平")
        .replace("fftScore", "周期共振强弱")
        .replace("accelZ", "情绪变化速度")
        .replace("volZ", "量能异常程度")
        .replace("emptyReason", "防守原因")
        .replace("residualScore", "残余风险评分")
        .replace("limitType", "涨跌停类型")
        .replace("openTimes", "封板打开次数")
        .replace("fdAmount", "封单规模")
        .replace("limitAmount", "板上成交额")
        .replace("upStat", "连板状态")
}

/**
 * 小标签（Badge）用于封板质量、连板数等轻量标识。
 */
@Composable
internal fun BadgeLabel(
    text: String,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = backgroundColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}
