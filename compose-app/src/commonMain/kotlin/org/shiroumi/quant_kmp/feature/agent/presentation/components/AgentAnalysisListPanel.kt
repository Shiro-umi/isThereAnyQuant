package org.shiroumi.quant_kmp.feature.agent.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import model.agent.AgentAnalysisResultDto
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme

/**
 * 分析结果列表面板
 *
 * 设计语言对齐 StockListContent（行情列表）：
 * - 扁平 ListItem，行项之间 2dp 间距，无独立卡片容器
 * - 选中态：左侧 4dp 圆角色条 + primaryContainer α0.14 极轻底色
 * - 行内容 padding 由 ListItem 自身承担，pane 外层不再加水平边距
 */
@Composable
fun AgentAnalysisListPanel(
    results: List<AgentAnalysisResultDto>,
    selectedId: String? = null,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onItemClick: (AgentAnalysisResultDto) -> Unit = {},
    onDeleteClick: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        when {
            isLoading && results.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            errorMessage != null && results.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        AssistChip(
                            onClick = onRetry,
                            label = { Text("重试") }
                        )
                    }
                }
            }

            results.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无分析记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = AgentTheme.Spacing.xs,
                        bottom = AgentTheme.Spacing.xs
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(results, key = { it.id }) { item ->
                        AnalysisResultItem(
                            item = item,
                            isSelected = item.id == selectedId,
                            onClick = { onItemClick(item) },
                            onDelete = { onDeleteClick(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisResultItem(
    item: AgentAnalysisResultDto,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rowColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.14f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "row_color"
    )

    val indicatorColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    val showIndicator = indicatorColor != Color.Transparent
    val indicatorWidth by animateDpAsState(
        targetValue = if (showIndicator) 4.dp else 0.dp,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "indicator_width"
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (showIndicator) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "indicator_alpha"
    )
    val indicatorHeight by animateDpAsState(
        targetValue = if (showIndicator) 32.dp else 20.dp,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "indicator_height"
    )
    val contentStartPadding by animateDpAsState(
        targetValue = if (showIndicator) 12.dp else 0.dp,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "content_start_padding"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(rowColor)
    ) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    text = item.title ?: "无标题",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = contentStartPadding)
                )
            },
            supportingContent = {
                Row(
                    modifier = Modifier.padding(start = contentStartPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)
                ) {
                    item.tsCode?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = item.createdAt.formatAnalysisTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            trailingContent = {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        )

        if (indicatorWidth > 0.dp || indicatorAlpha > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .width(indicatorWidth)
                    .height(indicatorHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(indicatorColor.copy(alpha = indicatorAlpha))
            )
        }
    }
}

private fun String.formatAnalysisTime(): String {
    if (length >= 16 && getOrNull(10) == 'T') {
        return "${substring(5, 10)} ${substring(11, 16)}"
    }
    return this
}
