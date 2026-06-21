package org.shiroumi.quant_kmp.feature.sentiment.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import model.candle.StrategySentimentResponse
import org.shiroumi.quant_kmp.feature.sentiment.presentation.ParameterSpec
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import org.shiroumi.quant_kmp.ui.core.adaptive.FunctionalRegion

@Composable
fun CompactChartCard(
    spec: ParameterSpec,
    history: List<StrategySentimentResponse>,
    latest: StrategySentimentResponse?,
    cardHeight: androidx.compose.ui.unit.Dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val lineColor = spec.color()
    val latestValue = latest?.let { spec.extractor(it) } ?: 0.0

    // 功能区容器统一走 FunctionalRegion：手机（本组件主场景）退化无卡 + 标准边距；宽屏 medium 卡。
    // 内容自带内边距，故 cardPadding 取 0。
    FunctionalRegion(
        shape = MaterialTheme.shapes.medium,
        cardPadding = PaddingValues(0.dp),
    ) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(
                            horizontal = AgentTheme.Spacing.md,
                            vertical = AgentTheme.Spacing.sm
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 10.dp, height = 3.dp)
                                .background(lineColor, RoundedCornerShape(AgentTheme.Shapes.small))
                        )
                        Text(
                            text = spec.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm),
                    ) {
                        Text(
                            text = spec.formatter(latestValue),
                            style = MaterialTheme.typography.titleMedium,
                            color = lineColor,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                MiniLineChart(
                    history = history,
                    extractor = spec.extractor,
                    lineColor = lineColor,
                    yMin = spec.yMin,
                    yMax = spec.yMax,
                    unit = spec.unit,
                    thresholds = spec.thresholds,
                    withFill = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = AgentTheme.Spacing.md),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    InlineDescriptionPanel(
                        semantics = spec.semantics,
                        tradingGuide = spec.tradingGuide,
                        modifier = Modifier.fillMaxWidth().padding(AgentTheme.Spacing.md),
                    )
                }
            }
        }
    }
}

@Composable
fun InlineDescriptionPanel(
    semantics: String,
    tradingGuide: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
        ) {
            Text(
                text = "参数语义",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = semantics,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
        ) {
            Text(
                text = "交易指导",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = tradingGuide,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
