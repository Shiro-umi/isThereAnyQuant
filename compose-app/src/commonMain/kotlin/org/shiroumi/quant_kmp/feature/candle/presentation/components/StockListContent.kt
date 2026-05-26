package org.shiroumi.quant_kmp.feature.candle.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import model.candle.Exchange
import model.candle.StockInfo
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import org.shiroumi.quant_kmp.ui.utils.formatPercentRaw
import org.shiroumi.quant_kmp.ui.utils.formatPriceRaw



/**
 * 股票列表内容组件
 *
 * 设计风格：单卡片中的 MD3 紧凑列表
 * - 行项不再表现为独立子卡片，只保留选中态容器
 * - 用 ListItem 原生结构组织名称、代码、价格和涨跌幅
 * - 价格与涨跌幅同一行，保持行情信息扫描效率
 */
@Composable
fun StockListContent(
    stocks: List<StockInfo>,
    selectedStock: StockInfo?,
    strategySelectionCodes: List<String> = emptyList(),
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onStockSelected: (StockInfo) -> Unit,
    onLoadMore: () -> Unit,
    onVisibleStocksChanged: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    showColHeader: Boolean = true,
    colHeaderTransparent: Boolean = false
) {
    // 监听滚动到底部，触发加载更多
    LaunchedEffect(listState, hasMore, isLoadingMore, isLoading) {
        snapshotFlow { listState.layoutInfo }
            .map { info ->
                val totalItems = info.totalItemsCount
                val lastVisibleItem = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItems > 0 && lastVisibleItem >= totalItems - 5
            }
            .distinctUntilChanged()
            .filter { it && hasMore && !isLoadingMore && !isLoading }
            .collect { onLoadMore() }
    }

    // 监听可见项变化，同步视口上下文给服务器 (用于 1s 实时行情推送)
    LaunchedEffect(listState, stocks) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .debounce(200L) // 防抖，避免滚动过程中频繁发送
            .map { visibleItems ->
                visibleItems.mapNotNull { item ->
                    stocks.getOrNull(item.index)?.code
                }
            }
            .distinctUntilChanged()
            .collect { visibleCodes ->
                if (visibleCodes.isNotEmpty()) {
                    onVisibleStocksChanged(visibleCodes)
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading && stocks.isEmpty() -> {
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

            stocks.isEmpty() -> {
                EmptyStockListState()
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (showColHeader) {
                        ColHeader(transparent = colHeaderTransparent)
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = AgentTheme.Spacing.xs,
                            bottom = AgentTheme.Spacing.xs
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(
                            items = stocks,
                            key = { it.code }
                        ) { stock ->
                            StockListItem(
                                stock = stock,
                                isSelected = selectedStock?.code == stock.code,
                                isStrategySelection = strategySelectionCodes.contains(stock.code),
                                onClick = { onStockSelected(stock) }
                            )
                        }

                        if (isLoadingMore) {
                            item(key = "loading_more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(AgentTheme.Spacing.md),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 1.5.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────
//  列标题行
// ─────────────────────────────────────────────────
@Composable
private fun ColHeader(
    modifier: Modifier = Modifier,
    transparent: Boolean = false
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (transparent) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AgentTheme.Spacing.md, vertical = AgentTheme.Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "名称 / 代码",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "现价",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
                Text(
                    text = "涨跌幅",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
//  股票列表行（扁平风格）
// ─────────────────────────────────────────────────
@Composable
private fun StockListItem(
    stock: StockInfo,
    isSelected: Boolean,
    isStrategySelection: Boolean = false,
    onClick: () -> Unit,
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

    val indicatorColor = when {
        isSelected && isStrategySelection -> MaterialTheme.colorScheme.tertiary
        isSelected -> MaterialTheme.colorScheme.primary
        isStrategySelection -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
        else -> Color.Transparent
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

    val priceColor = when {
        stock.changePercent > 0f -> MaterialTheme.colorScheme.error
        stock.changePercent < 0f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

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
                Row(
                    modifier = Modifier.padding(start = contentStartPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
                ) {
                    Text(
                        text = stock.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isStrategySelection) {
                        StrategySelectionTag()
                    }
                }
            },
            supportingContent = {
                Text(
                    text = "${exchangeLabel(stock.exchange)}  ${stock.code}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = contentStartPadding)
                )
            },
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
                ) {
                    Text(
                        text = formatPriceRaw(stock.latestPrice),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = priceColor
                    )
                    Text(
                        text = formatChangePercent(stock.changePercent),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = priceColor,
                        fontWeight = FontWeight.Medium
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

@Composable
private fun StrategySelectionTag(modifier: Modifier = Modifier) {
    Text(
        text = "选股",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        fontWeight = FontWeight.Medium,
        modifier = modifier
    )
}

@Composable
private fun EmptyStockListState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Text(
                text = "未找到股票",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun exchangeLabel(exchange: Exchange): String = when (exchange) {
    Exchange.SH -> "沪"
    Exchange.SZ -> "深"
    Exchange.BJ -> "京"
    Exchange.HK -> "港"
    Exchange.US -> "美"
}

private fun formatChangePercent(changePercent: Float): String {
    val sign = if (changePercent > 0f) "+" else ""
    return "$sign${formatPercentRaw(changePercent)}%"
}
