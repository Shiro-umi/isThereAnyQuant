package org.shiroumi.quant_kmp.feature.candle.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import model.candle.StockInfo
import model.candle.StrategySentimentResponse
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme

/**
 * 股票列表面板（桌面/平板端）
 *
 * 设计规范：
 * - 宽度限制 260–340dp，适配 Medium/Expanded 双栏布局
 * - 头部：面板标题 + 搜索框 + 分类 Tab
 * - 内容：StockListContent 扁平行列表
 * - 底部：当前选中股票快捷指示条
 */
@Composable
fun StockListPanel(
    stocks: List<StockInfo>,
    selectedStock: StockInfo?,
    searchQuery: String,
    strategySelectionCodes: List<String> = emptyList(),
    isStrategySelectionReady: Boolean = false,
    sentimentHistory: List<StrategySentimentResponse> = emptyList(),
    isLoading: Boolean,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onStockSelected: (StockInfo) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onLoadMore: () -> Unit,
    onVisibleStocksChanged: (List<String>) -> Unit = {},
    constrainWidth: Boolean = true,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var selectedTabIndex by remember { mutableStateOf(0) }

    LaunchedEffect(selectedTabIndex) {
        listState.scrollToItem(0)
    }

    val isPositionTab = selectedTabIndex == 0
    val displayStocks = if (isPositionTab) {
        stocks.filter { strategySelectionCodes.contains(it.code) }
    } else {
        stocks
    }
    val positionTabLoading = isPositionTab && !isStrategySelectionReady

    val widthModifier = if (constrainWidth) {
        Modifier.widthIn(min = 260.dp, max = 340.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    if (isCompact) {
        Column(
            modifier = modifier
                .then(widthModifier)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)
        ) {
            StockSearchField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                placeholder = "搜索股票...",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AgentTheme.Spacing.md),
                shape = RoundedCornerShape(AgentTheme.Shapes.medium),
                onFocused = { selectedTabIndex = 1 }
            )

            PanelHeader(
                selectedTabIndex = selectedTabIndex,
                onTabSelected = { selectedTabIndex = it },
                containerColor = Color.Transparent
            )

            StockListContent(
                stocks = displayStocks,
                selectedStock = selectedStock,
                strategySelectionCodes = strategySelectionCodes,
                isLoading = isLoading || positionTabLoading,
                isLoadingMore = if (isPositionTab) false else isLoadingMore,
                hasMore = if (isPositionTab) false else hasMore,
                onStockSelected = onStockSelected,
                onLoadMore = if (isPositionTab) ({}) else onLoadMore,
                onVisibleStocksChanged = onVisibleStocksChanged,
                modifier = Modifier.weight(1f),
                listState = listState,
                showColHeader = true,
                colHeaderTransparent = true
            )
        }
        return
    }

    Surface(
        modifier = modifier
            .then(widthModifier)
            .fillMaxHeight(),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.md)
        ) {
            StockSearchField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                placeholder = "搜索股票...",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AgentTheme.Spacing.md),
                shape = RoundedCornerShape(AgentTheme.Shapes.medium),
                onFocused = { selectedTabIndex = 1 }
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = AgentTheme.Spacing.md),
                shape = RoundedCornerShape(AgentTheme.Shapes.large),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = AgentTheme.Spacing.md, vertical = AgentTheme.Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)
                ) {
                    PanelHeader(
                        selectedTabIndex = selectedTabIndex,
                        onTabSelected = { selectedTabIndex = it }
                    )

                    StockListContent(
                        stocks = displayStocks,
                        selectedStock = selectedStock,
                        strategySelectionCodes = strategySelectionCodes,
                        isLoading = isLoading || positionTabLoading,
                        isLoadingMore = if (isPositionTab) false else isLoadingMore,
                        hasMore = if (isPositionTab) false else hasMore,
                        onStockSelected = onStockSelected,
                        onLoadMore = if (isPositionTab) ({}) else onLoadMore,
                        onVisibleStocksChanged = onVisibleStocksChanged,
                        modifier = Modifier.weight(1f),
                        listState = listState,
                        showColHeader = true
                    )

                    if (selectedStock != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = AgentTheme.Spacing.xs)
                                .clip(RoundedCornerShape(AgentTheme.Shapes.medium))
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.36f))
                                .padding(horizontal = AgentTheme.Spacing.md, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedStock.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Text(
                                    text = selectedStock.code,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.ShowChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 面板头部：标题 + 搜索框 + 分类 Tab
 */
@Composable
private fun PanelHeader(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow
) {
    PrimaryTabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = containerColor,
        divider = {}
    ) {
        StockFilterTab(
            text = "策略选股",
            selected = selectedTabIndex == 0,
            onClick = { onTabSelected(0) }
        )
        StockFilterTab(
            text = "全市场",
            selected = selectedTabIndex == 1,
            onClick = { onTabSelected(1) }
        )
    }
}

@Composable
private fun StockFilterTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Tab(
        onClick = onClick,
        selected = selected,
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    )
}
