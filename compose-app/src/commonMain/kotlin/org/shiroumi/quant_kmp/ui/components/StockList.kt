@file:OptIn(ExperimentalUuidApi::class)
package org.shiroumi.quant_kmp.ui.components

import kotlin.uuid.ExperimentalUuidApi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingFlat
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import model.candle.Exchange
import model.candle.StockInfo
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import kotlin.math.abs
import kotlin.math.round

/**
 * 股票列表状态
 */
sealed interface StockListState {
    /** 初始加载中 */
    data object InitialLoading : StockListState

    /** 刷新中 */
    data object Refreshing : StockListState

    /** 加载更多中 */
    data object LoadingMore : StockListState

    /** 加载完成 */
    data object Loaded : StockListState

    /** 加载完成，没有更多数据 */
    data object NoMoreData : StockListState

    /** 错误状态 */
    data class Error(val message: String) : StockListState

    /** 空状态 */
    data object Empty : StockListState
}

/**
 * 股票列表组件
 *
 * 支持无限滚动、下拉刷新、状态管理和性能优化
 *
 * @param stocks 股票列表数据
 * @param selectedStock 当前选中的股票
 * @param isLoading 是否正在初始加载
 * @param isLoadingMore 是否正在加载更多
 * @param hasMore 是否还有更多数据
 * @param error 错误信息
 * @param onSelect 选中股票回调
 * @param onLoadMore 加载更多回调
 * @param onRefresh 刷新回调
 * @param modifier 修饰符
 * @param listState LazyList状态，用于外部控制滚动
 * @param contentPadding 内容内边距
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun StockList(
    stocks: List<StockInfo>,
    selectedStock: StockInfo?,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    onSelect: (StockInfo) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val currentState = remember(stocks, isLoading, isLoadingMore, hasMore, error) {
        when {
            isLoading && stocks.isEmpty() -> StockListState.InitialLoading
            error != null && stocks.isEmpty() -> StockListState.Error(error)
            stocks.isEmpty() -> StockListState.Empty
            isLoadingMore -> StockListState.LoadingMore
            !hasMore -> StockListState.NoMoreData
            else -> StockListState.Loaded
        }
    }

    // 检测是否接近底部，触发加载更多
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0

            // 当最后一个可见项距离底部还有3个item时触发加载
            totalItems > 0 && lastVisibleItem >= totalItems - 3
        }
    }

    // 触发加载更多
    LaunchedEffect(shouldLoadMore, isLoadingMore, hasMore, isLoading) {
        if (shouldLoadMore && !isLoadingMore && hasMore && !isLoading) {
            onLoadMore()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (currentState) {
            is StockListState.InitialLoading -> {
                InitialLoadingIndicator()
            }

            is StockListState.Error -> {
                ErrorStateView(
                    message = currentState.message,
                    onRetry = onRefresh
                )
            }

            is StockListState.Empty -> {
                EmptyStateView(onRefresh = onRefresh)
            }

            else -> {
                StockListContent(
                    stocks = stocks,
                    selectedStock = selectedStock,
                    listState = listState,
                    isRefreshing = isLoading && stocks.isNotEmpty(),
                    isLoadingMore = isLoadingMore,
                    hasMore = hasMore,
                    error = error,
                    onSelect = onSelect,
                    onRefresh = onRefresh,
                    contentPadding = contentPadding
                )
            }
        }
    }
}

/**
 * 股票列表内容
 */
@Composable
private fun StockListContent(
    stocks: List<StockInfo>,
    selectedStock: StockInfo?,
    listState: LazyListState,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    onSelect: (StockInfo) -> Unit,
    onRefresh: () -> Unit,
    contentPadding: PaddingValues
) {
    val pullToRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        state = pullToRefreshState,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
        ) {
            // 股票列表项
            items(
                items = stocks,
                key = { it.id.toString() }
            ) { stock ->
                StockListItem(
                    stock = stock,
                    isSelected = stock.id == selectedStock?.id,
                    onClick = { onSelect(stock) }
                )
            }

            // 底部加载状态
            item(key = "footer") {
                ListFooter(
                    isLoadingMore = isLoadingMore,
                    hasMore = hasMore,
                    error = error,
                    onRetry = onRefresh
                )
            }
        }
    }
}

/**
 * 股票列表项
 */
@Composable
private fun StockListItem(
    stock: StockInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }

    val contentColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = backgroundColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AgentTheme.Spacing.md,
                    vertical = AgentTheme.Spacing.sm
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：股票代码和名称
            StockBasicInfo(
                stock = stock,
                isSelected = isSelected
            )

            // 中间：涨跌幅
            ChangePercentIndicator(changePercent = stock.changePercent)

            // 右侧：价格和成交量
            PriceAndVolume(stock = stock)
        }
    }
}

/**
 * 股票基本信息
 */
@Composable
private fun StockBasicInfo(
    stock: StockInfo,
    isSelected: Boolean
) {
    Column(modifier = Modifier.width(120.dp)) {
        // 股票名称
        Text(
            text = stock.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 股票代码和交易所
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
        ) {
            Text(
                text = stock.code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ExchangeBadge(exchange = stock.exchange)
        }
    }
}

/**
 * 交易所标签
 */
@Composable
private fun ExchangeBadge(exchange: Exchange) {
    val (text, color) = when (exchange) {
        Exchange.SH -> "沪" to MaterialTheme.colorScheme.primary
        Exchange.SZ -> "深" to MaterialTheme.colorScheme.tertiary
        Exchange.BJ -> "京" to MaterialTheme.colorScheme.secondary
        Exchange.HK -> "港" to MaterialTheme.colorScheme.error
        Exchange.US -> "美" to MaterialTheme.colorScheme.surfaceTint
    }

    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 涨跌幅指示器
 */
@Composable
private fun ChangePercentIndicator(changePercent: Float) {
    val (backgroundColor, contentColor, icon) = when {
        changePercent > 0 -> Triple(
            MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.error,
            Icons.Outlined.TrendingUp
        )
        changePercent < 0 -> Triple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.primary,
            Icons.Outlined.TrendingDown
        )
        else -> Triple(
            MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.outline,
            Icons.Outlined.TrendingFlat
        )
    }

    val sign = when {
        changePercent > 0 -> "+"
        else -> ""
    }

    // 格式化涨跌幅，避免使用 String.format (JS 平台不兼容)
    val formattedPercent = formatPercentValue(changePercent)

    Surface(
        color = backgroundColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = AgentTheme.Spacing.sm,
                vertical = AgentTheme.Spacing.xs
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "$sign$formattedPercent%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 价格和成交量
 */
@Composable
private fun PriceAndVolume(stock: StockInfo) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
    ) {
        // 当前价格
        val priceColor = when {
            stock.changePercent > 0 -> MaterialTheme.colorScheme.error
            stock.changePercent < 0 -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }

        val formattedPrice = formatPrice(stock.latestPrice)

        Text(
            text = formattedPrice,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = priceColor
        )

        // 成交量
        val formattedVolume = formatVolume(stock.volume)
        Text(
            text = formattedVolume,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 列表底部状态
 */
@Composable
private fun ListFooter(
    isLoadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AgentTheme.Spacing.md),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoadingMore -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "加载中...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            error != null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(AgentTheme.Spacing.xs))
                        Text("重试")
                    }
                }
            }

            !hasMore && !isLoadingMore -> {
                Text(
                    text = "没有更多数据了",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 初始加载指示器
 */
@Composable
private fun InitialLoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.md)
        ) {
            CircularProgressIndicator()
            Text(
                text = "加载股票数据中...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 错误状态视图
 */
@Composable
private fun ErrorStateView(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.lg),
            modifier = Modifier.padding(horizontal = AgentTheme.Spacing.xl)
        ) {
            Icon(
                imageVector = Icons.Outlined.TrendingFlat,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
            ) {
                Text(
                    text = "加载失败",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AgentTheme.Spacing.xs))
                Text("重新加载")
            }
        }
    }
}

/**
 * 空状态视图
 */
@Composable
private fun EmptyStateView(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.lg),
            modifier = Modifier.padding(horizontal = AgentTheme.Spacing.xl)
        ) {
            Icon(
                imageVector = Icons.Outlined.TrendingFlat,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
            ) {
                Text(
                    text = "暂无股票数据",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "点击刷新按钮重新加载",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AgentTheme.Spacing.xs))
                Text("刷新")
            }
        }
    }
}

// ==================== 格式化工具函数 ====================

/**
 * 格式化百分比
 * 避免使用 String.format (JS 平台不兼容)
 */
private fun formatPercentValue(value: Float): String {
    val rounded = round(value * 100) / 100
    val absValue = abs(rounded)

    return when {
        absValue == 0f -> "0.00"
        absValue < 10 -> {
            val intPart = rounded.toInt()
            val decPart = (abs(rounded - intPart) * 100).toInt()
            val decStr = if (decPart < 10) "0$decPart" else "$decPart"
            "$intPart.$decStr"
        }
        else -> {
            val intPart = rounded.toInt()
            val decPart = ((absValue - abs(intPart)) * 100).toInt()
            val decStr = if (decPart < 10) "0$decPart" else "$decPart"
            "$intPart.$decStr"
        }
    }
}

/**
 * 格式化价格
 */
private fun formatPrice(price: Float): String {
    val rounded = round(price * 100) / 100
    val intPart = rounded.toInt()
    val decPart = ((price - intPart) * 100).toInt().let { if (it < 0) -it else it }
    val decStr = if (decPart < 10) "0$decPart" else "$decPart"
    return "$intPart.$decStr"
}

/**
 * 格式化成交量
 * 转换为万/亿单位
 */
private fun formatVolume(volume: Float): String {
    return when {
        volume >= 100000000 -> {
            val yi = volume / 100000000
            val rounded = round(yi * 100) / 100
            "${formatPrice(rounded)}亿"
        }
        volume >= 10000 -> {
            val wan = volume / 10000
            val rounded = round(wan * 100) / 100
            "${formatPrice(rounded)}万"
        }
        else -> "${volume.toInt()}"
    }
}
