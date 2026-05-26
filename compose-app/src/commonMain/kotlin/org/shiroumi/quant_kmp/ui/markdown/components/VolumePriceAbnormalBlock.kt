package org.shiroumi.quant_kmp.ui.markdown.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.round
import org.shiroumi.quant_kmp.ui.markdown.VolumePriceBlockSpec
import org.shiroumi.quant_kmp.ui.theme.quantColors

/**
 * 异常量价关系分析自定义渲染块。
 *
 * 与其它报告自定义块保持正文式排版，仅将关键指标做轻量 chip 化。
 */
@Composable
fun VolumePriceAbnormalBlock(
    spec: VolumePriceBlockSpec,
    modifier: Modifier = Modifier
) {
    val anomalyColor = anomalyTypeColor(spec.anomalyType, MaterialTheme.colorScheme.secondary)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    text = "量价异常",
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

            BadgeLabel(
                text = spec.anomalyType,
                backgroundColor = anomalyColor.copy(alpha = 0.15f),
                textColor = anomalyColor
            )
        }

        anomalyTypeDescription(spec.anomalyType).takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f)
            )
        }

        if (spec.volumeRatio != null || spec.priceChange != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                spec.volumeRatio?.let { ratio ->
                    MetricChip(
                        label = "量比",
                        value = formatFloat(ratio, 1) + "x",
                        color = anomalyColor
                    )
                }
                spec.priceChange?.let { change ->
                    val isPositive = change >= 0
                    MetricChip(
                        label = "价格变动",
                        value = (if (isPositive) "+" else "") + formatFloat(change, 1) + "%",
                        color = if (isPositive) MaterialTheme.quantColors.bullish else MaterialTheme.quantColors.bearish
                    )
                }
            }
        }

        Text(
            text = naturalizeReportText(spec.analysis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
        )

        spec.institutionalInterpretation?.let { interpretation ->
            SectionBlock(
                title = "主力行为解读",
                content = interpretation,
                indicatorColor = MaterialTheme.colorScheme.secondary
            )
        }

        spec.sentimentValidation?.let { validation ->
            EditorialCalloutBlock(
                text = validation,
                accentColor = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

/** 指标小标签 */
@Composable
private fun MetricChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    AssistChip(
        modifier = modifier,
        onClick = {},
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            }
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.08f)
        )
    )
}

/** 基于异常类型返回主色调，[defaultColor] 为未匹配时的回退色。A 股惯例：红涨绿跌。 */
@Composable
private fun anomalyTypeColor(type: String, defaultColor: Color): Color {
    val semantic = MaterialTheme.quantColors
    return when {
        type.contains("上涨") || type.contains("放量涨") -> semantic.bullish
        type.contains("下跌") || type.contains("放量跌") -> semantic.bearish
        type.contains("天量") -> semantic.warning
        type.contains("地量") -> semantic.neutral
        else -> defaultColor
    }
}

/** JS 安全的浮点数格式化：保留 [scale] 位小数 */
private fun formatFloat(value: Float, scale: Int): String {
    val factor = when (scale) { 0 -> 1f; 1 -> 10f; 2 -> 100f; else -> 1000f }
    val rounded = round(value * factor) / factor
    val str = rounded.toDouble().toString()
    val dot = str.indexOf('.')
    return if (dot >= 0) {
        val decimals = str.length - dot - 1
        if (decimals >= scale) str.substring(0, dot + 1 + scale)
        else str + "0".repeat(scale - decimals)
    } else {
        str + "." + "0".repeat(scale)
    }
}

/** 异常类型的自然语言描述 */
private fun anomalyTypeDescription(type: String): String = when {
    type.contains("放量上涨") -> "价格上涨伴随成交量显著放大，资金主动买入意愿强"
    type.contains("缩量上涨") -> "价格上涨但成交量萎缩，上涨动能不足，需警惕见顶"
    type.contains("放量下跌") -> "价格下跌伴随成交量放大，恐慌抛售或主力出货迹象"
    type.contains("缩量下跌") -> "价格下跌但成交量萎缩，正常回调或洗盘特征"
    type.contains("天量") -> "成交量异常放大至近期极值，多空分歧剧烈"
    type.contains("地量") -> "成交量极度萎缩至近期低谷，市场交投清淡"
    else -> ""
}
