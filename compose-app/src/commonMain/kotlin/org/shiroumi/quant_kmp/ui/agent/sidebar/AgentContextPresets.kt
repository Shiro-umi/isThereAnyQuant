package org.shiroumi.quant_kmp.ui.agent.sidebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import model.agent.AgentAnalysisType
import model.candle.StockInfo
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme

private val sidebarSkillPresets = listOf(
    SkillPreset(
        skillId = "entry-exit-analysis",
        analysisType = AgentAnalysisType.ENTRY_EXIT.code,
        label = "买卖点分析",
        icon = Icons.Outlined.PlayCircle,
        description = "多周期出入场计划",
        promptTemplate = "请分析{name}({code})的买卖点。"
    ),
    SkillPreset(
        skillId = "research-report-outlook-analysis",
        analysisType = AgentAnalysisType.RESEARCH_REPORT.code,
        label = "研报分析",
        icon = Icons.Outlined.AutoStories,
        description = "个股与行业研报联动",
        promptTemplate = "请先查询{name}({code})的个股研报，再根据研报中的行业名称查询行业研报，重点分析摘要和行业信息，对比多份个股研报与行业研报后，给出个股结合行业的预期分析。"
    ),
    SkillPreset(
        skillId = "trend-analysis",
        analysisType = AgentAnalysisType.MARKET_SENTIMENT.code,
        label = "趋势分析",
        icon = Icons.AutoMirrored.Outlined.TrendingUp,
        description = "趋势方向与阶段",
        promptTemplate = "请结合{name}({code})当前走势，使用 trend-analysis 分析趋势方向、强度、阶段和转折风险。"
    ),
    SkillPreset(
        skillId = "risk-assessment",
        analysisType = AgentAnalysisType.GENERAL.code,
        label = "风险评估",
        icon = Icons.Outlined.Shield,
        description = "止损与仓位管理",
        promptTemplate = "请结合{name}({code})当前走势，使用 risk-assessment 评估止损位、风险收益比、仓位建议和主要风险。"
    )
)

@Composable
fun AgentContextPresets(
    selectedStock: StockInfo?,
    onPresetSelected: (AgentPresetPrompt) -> Unit,
    useTransparentBackground: Boolean = false,
    modifier: Modifier = Modifier
) {
    var lastVisibleStock by remember { mutableStateOf<StockInfo?>(selectedStock) }

    LaunchedEffect(selectedStock) {
        if (selectedStock != null) {
            lastVisibleStock = selectedStock
        }
    }

    AnimatedVisibility(
        visible = selectedStock != null,
        enter = expandVertically(
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top
        ) + fadeIn(
            animationSpec = tween(durationMillis = 220, delayMillis = 40, easing = LinearOutSlowInEasing)
        ),
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing),
            shrinkTowards = Alignment.Top
        ) + fadeOut(
            animationSpec = tween(durationMillis = 120, easing = FastOutLinearInEasing)
        ),
        modifier = modifier
    ) {
        lastVisibleStock?.let { stock ->
            ContextAndActionsCard(
                stock = stock,
                onPresetSelected = onPresetSelected,
                useTransparentBackground = useTransparentBackground
            )
        }
    }
}

@Composable
private fun ContextAndActionsCard(
    stock: StockInfo,
    onPresetSelected: (AgentPresetPrompt) -> Unit,
    useTransparentBackground: Boolean = false
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ContextHeader(stock = stock)
            QuickActionsRow(stock = stock, onPresetSelected = onPresetSelected)
        }
    }

    if (useTransparentBackground) {
        content()
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AgentTheme.Shapes.large),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            content()
        }
    }
}

@Composable
private fun ContextHeader(
    stock: StockInfo
) {
    val isPositive = stock.changePercent >= 0
    val changeColor = if (isPositive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Box(
                    modifier = Modifier.padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CandlestickChart,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stock.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${stock.code} · 将作为本轮对话上下文",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ChangeBadge(
            text = "${if (isPositive) "+" else ""}${roundToTwoDecimals(stock.changePercent)}%",
            containerColor = changeColor.copy(alpha = 0.12f),
            contentColor = changeColor
        )
    }
}

@Composable
private fun QuickActionsRow(
    stock: StockInfo,
    onPresetSelected: (AgentPresetPrompt) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sidebarSkillPresets.forEach { preset ->
            val prompt = preset.promptTemplate
                .replace("{name}", stock.name)
                .replace("{code}", stock.code)

            AssistChip(
                onClick = {
                    onPresetSelected(
                        AgentPresetPrompt(
                            prompt = prompt,
                            analysisType = preset.analysisType
                        )
                    )
                },
                label = {
                    Text(
                        text = preset.label,
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = preset.icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun ChangeBadge(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

fun roundToTwoDecimals(value: Float): String {
    val rounded = kotlin.math.round(value * 100) / 100.0
    val str = rounded.toString()
    val dotIndex = str.indexOf('.')
    return if (dotIndex == -1) {
        "$str.00"
    } else {
        val decimals = str.length - dotIndex - 1
        when {
            decimals == 1 -> "${str}0"
            decimals >= 2 -> str.substring(0, dotIndex + 3)
            else -> str
        }
    }
}
