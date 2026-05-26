package org.shiroumi.quant_kmp.feature.candle.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import model.candle.CandleChartData
import model.candle.CandlePeriod
import model.candle.Exchange
import model.candle.MarketStatus
import model.candle.StockInfo
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import org.shiroumi.quant_kmp.ui.components.chart.CandleChartConfig
import org.shiroumi.quant_kmp.ui.components.chart.CandleChartPanel
import org.shiroumi.quant_kmp.ui.utils.formatMarketCapRaw
import org.shiroumi.quant_kmp.ui.utils.formatPercentRaw
import org.shiroumi.quant_kmp.ui.utils.formatPriceRaw
import org.shiroumi.quant_kmp.ui.utils.formatVolumeRaw
import util.formatTimestampShort

/**
 * 蜡烛图图表区域
 *
 * 语义重组：
 * - 顶部为股票摘要卡，可展开查看补充行情与基本面信息
 * - 下方为图表工作卡，将周期和指标控制并入同一工作区
 */
@Composable
fun CandleChartSection(
    stockInfo: StockInfo?,
    chartData: CandleChartData?,
    marketStatus: MarketStatus,
    selectedPeriod: CandlePeriod,
    isLoading: Boolean,
    showVolume: Boolean,
    showRsi: Boolean,
    showMacd: Boolean,
    showEma: Boolean,
    showMa: Boolean,
    onPeriodSelected: (CandlePeriod) -> Unit,
    onToggleVolume: (Boolean) -> Unit,
    onToggleRsi: (Boolean) -> Unit,
    onToggleMacd: (Boolean) -> Unit,
    onToggleEma: (Boolean) -> Unit,
    onToggleMa: (Boolean) -> Unit,
    onShowStockList: (() -> Unit)? = null,
    isCompact: Boolean = false,
    loadingMessage: String? = null,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (stockInfo == null) {
            EmptySelectionState()
        } else {
            var hoverCandleIndex by remember { mutableIntStateOf(-1) }
            var summaryCollapsedHeightPx by remember(stockInfo.code) { mutableIntStateOf(0) }
            val summaryCollapsedHeightDp = with(LocalDensity.current) { summaryCollapsedHeightPx.toDp() }

            Box(modifier = Modifier.fillMaxSize()) {
                val sectionSpacing = if (isCompact) 0.dp else AgentTheme.Spacing.md
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(sectionSpacing)
                ) {
                    Spacer(modifier = Modifier.height(summaryCollapsedHeightDp))

                    ChartWorkspaceCard(
                        chartData = chartData,
                        selectedPeriod = selectedPeriod,
                        isLoading = isLoading,
                        loadingMessage = loadingMessage,
                        errorMessage = errorMessage,
                        showVolume = showVolume,
                        showRsi = showRsi,
                        showMacd = showMacd,
                        showEma = showEma,
                        showMa = showMa,
                        onPeriodSelected = onPeriodSelected,
                        onToggleVolume = onToggleVolume,
                        onToggleRsi = onToggleRsi,
                        onToggleMacd = onToggleMacd,
                        onToggleEma = onToggleEma,
                        onToggleMa = onToggleMa,
                        onCandleHover = { index -> hoverCandleIndex = index },
                        isCompact = isCompact,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }

                StockSummaryCard(
                    stockInfo = stockInfo,
                    chartData = chartData,
                    marketStatus = marketStatus,
                    hoverCandleIndex = hoverCandleIndex,
                    onCollapsedHeightMeasured = { height ->
                        if (height > 0) summaryCollapsedHeightPx = height
                    },
                    onShowStockList = onShowStockList,
                    isCompact = isCompact,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .zIndex(2f)
                )
            }
        }
    }
}

@Composable
private fun StockSummaryCard(
    stockInfo: StockInfo,
    chartData: CandleChartData?,
    marketStatus: MarketStatus,
    hoverCandleIndex: Int,
    onCollapsedHeightMeasured: (Int) -> Unit,
    onShowStockList: (() -> Unit)?,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    val candle = if (hoverCandleIndex >= 0 && chartData != null) {
        chartData.candles.getOrNull(hoverCandleIndex)
    } else null

    val displayPrice = candle?.close ?: stockInfo.latestPrice
    val displayChangeAmount = candle?.let {
        val prevClose = if (hoverCandleIndex > 0) {
            chartData?.candles?.getOrNull(hoverCandleIndex - 1)?.close
        } else {
            stockInfo.prevClose
        }
        it.close - (prevClose ?: it.open)
    } ?: stockInfo.changeAmount
    val displayChangePercent = candle?.let {
        val prevClose = if (hoverCandleIndex > 0) {
            chartData?.candles?.getOrNull(hoverCandleIndex - 1)?.close
        } else {
            stockInfo.prevClose
        }
        val change = it.close - (prevClose ?: it.open)
        if (prevClose != null && prevClose != 0f) change / prevClose * 100 else 0f
    } ?: stockInfo.changePercent

    val isPositive = displayChangeAmount >= 0
    val changeColor = if (isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary

    var expanded by remember(stockInfo.code) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "summary_arrow_rotation"
    )
    StockSummarySharedCard(
        stockInfo = stockInfo,
        marketStatus = marketStatus,
        hoverCandleIndex = hoverCandleIndex,
        expanded = expanded,
        onToggleExpanded = { expanded = !expanded },
        arrowRotation = arrowRotation,
        displayPrice = displayPrice,
        displayChangeAmount = displayChangeAmount,
        displayChangePercent = displayChangePercent,
        changeColor = changeColor,
        onCollapsedHeightMeasured = onCollapsedHeightMeasured,
        onShowStockList = onShowStockList,
        isCompact = isCompact,
        modifier = modifier
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun StockSummarySharedCard(
    stockInfo: StockInfo,
    marketStatus: MarketStatus,
    hoverCandleIndex: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    arrowRotation: Float,
    displayPrice: Float,
    displayChangeAmount: Float,
    displayChangePercent: Float,
    changeColor: Color,
    onCollapsedHeightMeasured: (Int) -> Unit,
    onShowStockList: (() -> Unit)?,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    SharedTransitionLayout {
        val cardSharedState = rememberSharedContentState("stock-summary-card-${stockInfo.code}")
        val iconSharedState = rememberSharedContentState("stock-summary-icon-${stockInfo.code}")
        val titleSharedState = rememberSharedContentState("stock-summary-title-${stockInfo.code}")
        val codeSharedState = rememberSharedContentState("stock-summary-code-${stockInfo.code}")
        val exchangeSharedState = rememberSharedContentState("stock-summary-exchange-${stockInfo.code}")
        val statusSharedState = rememberSharedContentState("stock-summary-status-${stockInfo.code}")
        val priceSharedState = rememberSharedContentState("stock-summary-price-${stockInfo.code}")
        val percentSharedState = rememberSharedContentState("stock-summary-percent-${stockInfo.code}")
        val arrowSharedState = rememberSharedContentState("stock-summary-arrow-${stockInfo.code}")

        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                fadeIn(animationSpec = tween(260, easing = LinearOutSlowInEasing)) togetherWith
                    fadeOut(animationSpec = tween(160, easing = FastOutLinearInEasing))
            },
            label = "stock_summary_layout"
        ) { isExpanded ->
            if (isExpanded) {
                val animatedScope = this
                ExpandedStockSummaryCard(
                    stockInfo = stockInfo,
                    marketStatus = marketStatus,
                    hoverCandleIndex = hoverCandleIndex,
                    onToggleExpanded = onToggleExpanded,
                    arrowRotation = arrowRotation,
                    displayPrice = displayPrice,
                    displayChangeAmount = displayChangeAmount,
                    displayChangePercent = displayChangePercent,
                    changeColor = changeColor,
                    cardSharedState = cardSharedState,
                    iconSharedState = iconSharedState,
                    titleSharedState = titleSharedState,
                    codeSharedState = codeSharedState,
                    exchangeSharedState = exchangeSharedState,
                    statusSharedState = statusSharedState,
                    priceSharedState = priceSharedState,
                    percentSharedState = percentSharedState,
                    arrowSharedState = arrowSharedState,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = animatedScope,
                    onShowStockList = onShowStockList,
                    isCompact = isCompact
                )
            } else {
                CollapsedStockSummaryCard(
                    stockInfo = stockInfo,
                    marketStatus = marketStatus,
                    hoverCandleIndex = hoverCandleIndex,
                    onToggleExpanded = onToggleExpanded,
                    onCollapsedHeightMeasured = onCollapsedHeightMeasured,
                    arrowRotation = arrowRotation,
                    displayPrice = displayPrice,
                    displayChangePercent = displayChangePercent,
                    cardSharedState = cardSharedState,
                    iconSharedState = iconSharedState,
                    titleSharedState = titleSharedState,
                    codeSharedState = codeSharedState,
                    exchangeSharedState = exchangeSharedState,
                    statusSharedState = statusSharedState,
                    priceSharedState = priceSharedState,
                    percentSharedState = percentSharedState,
                    arrowSharedState = arrowSharedState,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                    onShowStockList = onShowStockList,
                    isCompact = isCompact,
                    modifier = modifier
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CollapsedStockSummaryCard(
    stockInfo: StockInfo,
    marketStatus: MarketStatus,
    hoverCandleIndex: Int,
    onToggleExpanded: () -> Unit,
    onCollapsedHeightMeasured: (Int) -> Unit,
    arrowRotation: Float,
    displayPrice: Float,
    displayChangePercent: Float,
    cardSharedState: SharedTransitionScope.SharedContentState,
    iconSharedState: SharedTransitionScope.SharedContentState,
    titleSharedState: SharedTransitionScope.SharedContentState,
    codeSharedState: SharedTransitionScope.SharedContentState,
    exchangeSharedState: SharedTransitionScope.SharedContentState,
    statusSharedState: SharedTransitionScope.SharedContentState,
    priceSharedState: SharedTransitionScope.SharedContentState,
    percentSharedState: SharedTransitionScope.SharedContentState,
    arrowSharedState: SharedTransitionScope.SharedContentState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onShowStockList: (() -> Unit)?,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) = with(sharedTransitionScope) {
    val cardModifier = Modifier
        .then(modifier)
        .fillMaxWidth()
        .onSizeChanged { onCollapsedHeightMeasured(it.height) }
        .sharedBounds(
            sharedContentState = cardSharedState,
            animatedVisibilityScope = animatedVisibilityScope
        )
    val cardShape = if (isCompact) MaterialTheme.shapes.extraSmall else RoundedCornerShape(AgentTheme.Shapes.large)
    val horizontalPadding = if (isCompact) 16.dp else 20.dp
    val verticalPadding = if (isCompact) 14.dp else 18.dp

    Surface(
        modifier = cardModifier,
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpanded() }
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SummaryLeadingIcon(
                modifier = Modifier.sharedElement(
                    sharedContentState = iconSharedState,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stockInfo.name,
                        modifier = Modifier.sharedBounds(
                            sharedContentState = titleSharedState,
                            animatedVisibilityScope = animatedVisibilityScope
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    ExchangeBadge(
                        exchange = stockInfo.exchange,
                        modifier = Modifier.sharedElement(
                            sharedContentState = exchangeSharedState,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    )
                }

                Text(
                    text = stockInfo.code,
                    modifier = Modifier.sharedBounds(
                        sharedContentState = codeSharedState,
                        animatedVisibilityScope = animatedVisibilityScope
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SummaryStatus(
                    marketStatus = marketStatus,
                    hoverCandleIndex = hoverCandleIndex,
                    modifier = Modifier.sharedElement(
                        sharedContentState = statusSharedState,
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = formatPriceRaw(displayPrice),
                    modifier = Modifier.sharedBounds(
                        sharedContentState = priceSharedState,
                        animatedVisibilityScope = animatedVisibilityScope
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (displayChangePercent >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
                ChangeBadge(
                    text = "${if (displayChangePercent >= 0) "+" else ""}${formatPercentRaw(displayChangePercent)}%",
                    containerColor = if (displayChangePercent >= 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                    contentColor = if (displayChangePercent >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.sharedElement(
                        sharedContentState = percentSharedState,
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                )
            }

            SummaryActionButtons(
                arrowRotation = arrowRotation,
                onShowStockList = onShowStockList,
                arrowModifier = Modifier.sharedElement(
                    sharedContentState = arrowSharedState,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ExpandedStockSummaryCard(
    stockInfo: StockInfo,
    marketStatus: MarketStatus,
    hoverCandleIndex: Int,
    onToggleExpanded: () -> Unit,
    arrowRotation: Float,
    displayPrice: Float,
    displayChangeAmount: Float,
    displayChangePercent: Float,
    changeColor: Color,
    cardSharedState: SharedTransitionScope.SharedContentState,
    iconSharedState: SharedTransitionScope.SharedContentState,
    titleSharedState: SharedTransitionScope.SharedContentState,
    codeSharedState: SharedTransitionScope.SharedContentState,
    exchangeSharedState: SharedTransitionScope.SharedContentState,
    statusSharedState: SharedTransitionScope.SharedContentState,
    priceSharedState: SharedTransitionScope.SharedContentState,
    percentSharedState: SharedTransitionScope.SharedContentState,
    arrowSharedState: SharedTransitionScope.SharedContentState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onShowStockList: (() -> Unit)?,
    isCompact: Boolean
) = with(sharedTransitionScope) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .sharedBounds(
            sharedContentState = cardSharedState,
            animatedVisibilityScope = animatedVisibilityScope
        )
    val cardShape = if (isCompact) MaterialTheme.shapes.extraSmall else RoundedCornerShape(AgentTheme.Shapes.large)
    val contentPadding = if (isCompact) 16.dp else 20.dp

    Surface(
        modifier = cardModifier,
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpanded() }
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                SummaryLeadingIcon(
                    modifier = Modifier.sharedElement(
                        sharedContentState = iconSharedState,
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stockInfo.name,
                            modifier = Modifier.sharedBounds(
                                sharedContentState = titleSharedState,
                                animatedVisibilityScope = animatedVisibilityScope
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        ExchangeBadge(
                            exchange = stockInfo.exchange,
                            modifier = Modifier.sharedElement(
                                sharedContentState = exchangeSharedState,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        )
                    }

                    Text(
                        text = stockInfo.code,
                        modifier = Modifier.sharedBounds(
                            sharedContentState = codeSharedState,
                            animatedVisibilityScope = animatedVisibilityScope
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SummaryStatus(
                        marketStatus = marketStatus,
                        hoverCandleIndex = hoverCandleIndex,
                        modifier = Modifier.sharedElement(
                            sharedContentState = statusSharedState,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    )
                }

                SummaryActionButtons(
                    arrowRotation = arrowRotation,
                    onShowStockList = onShowStockList,
                    arrowModifier = Modifier.sharedElement(
                        sharedContentState = arrowSharedState,
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                )
            }

            if (isCompact) {
                // 手机端：价格与详情垂直排列，最大化可读性
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 价格行：更紧凑的水平排布
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = formatPriceRaw(displayPrice),
                                modifier = Modifier.sharedBounds(
                                    sharedContentState = priceSharedState,
                                    animatedVisibilityScope = animatedVisibilityScope
                                ),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = changeColor
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${if (displayChangeAmount >= 0) "+" else ""}${formatPriceRaw(displayChangeAmount)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = changeColor
                                )
                                ChangeBadge(
                                    text = "${if (displayChangePercent >= 0) "+" else ""}${formatPercentRaw(displayChangePercent)}%",
                                    containerColor = changeColor.copy(alpha = 0.12f),
                                    contentColor = changeColor,
                                    modifier = Modifier.sharedElement(
                                        sharedContentState = percentSharedState,
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "成交量 ${formatVolumeRaw(stockInfo.volume)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "换手 ${formatPercentRaw(stockInfo.turnover)}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        thickness = 0.5.dp
                    )

                    DenseInfoGrid(stockInfo = stockInfo, isCompact = true)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1.1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = formatPriceRaw(displayPrice),
                            modifier = Modifier.sharedBounds(
                                sharedContentState = priceSharedState,
                                animatedVisibilityScope = animatedVisibilityScope
                            ),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = changeColor
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${if (displayChangeAmount >= 0) "+" else ""}${formatPriceRaw(displayChangeAmount)}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = changeColor
                            )
                            ChangeBadge(
                                text = "${if (displayChangePercent >= 0) "+" else ""}${formatPercentRaw(displayChangePercent)}%",
                                containerColor = changeColor.copy(alpha = 0.12f),
                                contentColor = changeColor,
                                modifier = Modifier.sharedElement(
                                    sharedContentState = percentSharedState,
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                            )
                        }
                        Text(
                            text = "成交量 ${formatVolumeRaw(stockInfo.volume)} · 换手 ${formatPercentRaw(stockInfo.turnover)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1.1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DenseInfoGrid(stockInfo = stockInfo, isCompact = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryLeadingIcon(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(AgentTheme.Shapes.medium)
    ) {
        Box(
            modifier = Modifier.padding(10.dp),
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

@Composable
private fun SummaryActionButtons(
    arrowRotation: Float,
    onShowStockList: (() -> Unit)? = null,
    arrowModifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onShowStockList != null) {
            IconButton(onClick = onShowStockList) {
                Icon(
                    imageVector = Icons.Outlined.List,
                    contentDescription = "打开股票列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.KeyboardArrowDown,
            contentDescription = "展开或收起详情",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = arrowModifier.rotate(arrowRotation)
        )
    }
}

@Composable
private fun SummaryStatus(
    marketStatus: MarketStatus,
    hoverCandleIndex: Int,
    modifier: Modifier = Modifier
) {
    if (hoverCandleIndex >= 0) {
        Text(
            text = "查看历史 K 线",
            modifier = modifier,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    } else {
        LiveIndicator(
            isLive = marketStatus == MarketStatus.OPEN,
            modifier = modifier
        )
    }
}

@Composable
private fun MetricRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        MetricCompactItem(
            label = leftLabel,
            value = leftValue,
            modifier = Modifier.weight(1f)
        )
        MetricCompactItem(
            label = rightLabel,
            value = rightValue,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DenseInfoGrid(
    stockInfo: StockInfo,
    isCompact: Boolean = false
) {
    val tradingItems = listOf(
        "今开" to formatPriceRaw(stockInfo.openPrice),
        "昨收" to formatPriceRaw(stockInfo.prevClose),
        "最高" to formatPriceRaw(stockInfo.dayHigh),
        "最低" to formatPriceRaw(stockInfo.dayLow),
        "成交量" to formatVolumeRaw(stockInfo.volume),
        "换手率" to "${formatPercentRaw(stockInfo.turnover)}%"
    )
    val valuationItems = buildList {
        add("总市值" to formatMarketCapRaw(stockInfo.marketCap))
        stockInfo.peRatio?.let { add("PE" to formatPriceRaw(it)) }
        stockInfo.pbRatio?.let { add("PB" to formatPriceRaw(it)) }
        add("涨跌额" to "${if (stockInfo.changeAmount >= 0) "+" else ""}${formatPriceRaw(stockInfo.changeAmount)}")
        add("涨跌幅" to "${if (stockInfo.changePercent >= 0) "+" else ""}${formatPercentRaw(stockInfo.changePercent)}%")
    }
    val profileItems = listOf(
        "行业" to stockInfo.industry.ifBlank { "暂无" },
        "板块" to stockInfo.sector.ifBlank { "暂无" },
        "交易所" to stockInfo.exchange.name,
        "代码" to stockInfo.fullCode,
        "更新" to stockInfo.updateTime.formatTimestampShort()
    )

    if (isCompact) {
        // 手机端：垂直分组，每组内部 FlowRow 自动换行，最多 3 列
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CompactInfoGroup(title = "交易", items = tradingItems)
            CompactInfoGroup(title = "估值", items = valuationItems)
            CompactInfoGroup(title = "归属", items = profileItems)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            InfoGroupColumn(
                title = "交易",
                items = tradingItems,
                modifier = Modifier.weight(1f)
            )
            InfoGroupColumn(
                title = "估值",
                items = valuationItems,
                modifier = Modifier.weight(1f)
            )
            InfoGroupColumn(
                title = "归属",
                items = profileItems,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CompactInfoGroup(
    title: String,
    items: List<Pair<String, String>>
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3
        ) {
            items.forEach { (label, value) ->
                MetricCompactItem(
                    label = label,
                    value = value,
                    modifier = Modifier.widthIn(min = 80.dp, max = 120.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoGroupColumn(
    title: String,
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        items.forEach { (label, value) ->
            MetricCompactItem(
                label = label,
                value = value
            )
        }
    }
}

@Composable
private fun MetricCompactItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PriceInsightRow(
    price: Float,
    changeAmount: Float,
    changePercent: Float,
    volume: Float,
    turnover: Float,
    changeColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = formatPriceRaw(price),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = changeColor
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${if (changeAmount >= 0) "+" else ""}${formatPriceRaw(changeAmount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = changeColor
                )
                ChangeBadge(
                    text = "${if (changePercent >= 0) "+" else ""}${formatPercentRaw(changePercent)}%",
                    containerColor = changeColor.copy(alpha = 0.12f),
                    contentColor = changeColor
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "成交量 ${formatVolumeRaw(volume)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "换手 ${formatPercentRaw(turnover)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChartWorkspaceCard(
    chartData: CandleChartData?,
    selectedPeriod: CandlePeriod,
    isLoading: Boolean,
    showVolume: Boolean,
    showRsi: Boolean,
    showMacd: Boolean,
    showEma: Boolean,
    showMa: Boolean,
    onPeriodSelected: (CandlePeriod) -> Unit,
    onToggleVolume: (Boolean) -> Unit,
    onToggleRsi: (Boolean) -> Unit,
    onToggleMacd: (Boolean) -> Unit,
    onToggleEma: (Boolean) -> Unit,
    onToggleMa: (Boolean) -> Unit,
    onCandleHover: (Int) -> Unit,
    isCompact: Boolean,
    loadingMessage: String? = null,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val activeSubChartCount = listOf(showVolume, showRsi, showMacd).count { it }

    val cardShape = if (isCompact) RoundedCornerShape(AgentTheme.Shapes.medium) else RoundedCornerShape(AgentTheme.Shapes.large)
    val outerPadding = if (isCompact) 12.dp else 16.dp
    val innerSpacing = if (isCompact) 8.dp else 12.dp
    val innerShape = if (isCompact) RoundedCornerShape(AgentTheme.Shapes.small) else RoundedCornerShape(AgentTheme.Shapes.large)

    Card(
        modifier = modifier,
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(outerPadding),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            ChartControlsSection(
                selectedPeriod = selectedPeriod,
                onPeriodSelected = onPeriodSelected,
                showVolume = showVolume,
                showRsi = showRsi,
                showMacd = showMacd,
                showEma = showEma,
                showMa = showMa,
                activeSubChartCount = activeSubChartCount,
                onToggleVolume = onToggleVolume,
                onToggleRsi = onToggleRsi,
                onToggleMacd = onToggleMacd,
                onToggleEma = onToggleEma,
                onToggleMa = onToggleMa
            )

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = innerShape
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isLoading) {
                        LoadingChartState(message = loadingMessage)
                    } else if (chartData != null) {
                        ChartContent(
                            chartData = chartData,
                            showVolume = showVolume,
                            showRsi = showRsi,
                            showMacd = showMacd,
                            showEma = showEma,
                            showMa = showMa,
                            onCandleHover = onCandleHover,
                            isCompact = isCompact
                        )
                    } else {
                        ErrorChartState(message = errorMessage)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartControlsSection(
    selectedPeriod: CandlePeriod,
    onPeriodSelected: (CandlePeriod) -> Unit,
    showVolume: Boolean,
    showRsi: Boolean,
    showMacd: Boolean,
    showEma: Boolean,
    showMa: Boolean,
    activeSubChartCount: Int,
    onToggleVolume: (Boolean) -> Unit,
    onToggleRsi: (Boolean) -> Unit,
    onToggleMacd: (Boolean) -> Unit,
    onToggleEma: (Boolean) -> Unit,
    onToggleMa: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PeriodSelector(
            selectedPeriod = selectedPeriod,
            onPeriodSelected = onPeriodSelected
        )
        IndicatorSelector(
            showVolume = showVolume,
            showRsi = showRsi,
            showMacd = showMacd,
            showEma = showEma,
            showMa = showMa,
            activeSubChartCount = activeSubChartCount,
            onToggleVolume = onToggleVolume,
            onToggleRsi = onToggleRsi,
            onToggleMacd = onToggleMacd,
            onToggleEma = onToggleEma,
            onToggleMa = onToggleMa
        )
    }
}

@Composable
private fun PeriodSelector(
    selectedPeriod: CandlePeriod,
    onPeriodSelected: (CandlePeriod) -> Unit
) {
    val periods = remember {
        listOf(
            CandlePeriod.DAY to "日K",
            CandlePeriod.WEEK to "周K",
            CandlePeriod.MONTH to "月K",
            CandlePeriod.MIN_60 to "60分",
            CandlePeriod.MIN_30 to "30分",
            CandlePeriod.MIN_15 to "15分",
            CandlePeriod.MIN_5 to "5分"
        )
    }
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = periods.firstOrNull { it.first == selectedPeriod }?.second ?: "日K"

    Box {
        ControlMenuTrigger(
            label = currentLabel,
            expanded = expanded,
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            periods.forEach { (period, label) ->
                val selected = period == selectedPeriod
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        expanded = false
                        onPeriodSelected(period)
                    }
                )
            }
        }
    }
}

@Composable
private fun IndicatorSelector(
    showVolume: Boolean,
    showRsi: Boolean,
    showMacd: Boolean,
    showEma: Boolean,
    showMa: Boolean,
    activeSubChartCount: Int,
    onToggleVolume: (Boolean) -> Unit,
    onToggleRsi: (Boolean) -> Unit,
    onToggleMacd: (Boolean) -> Unit,
    onToggleEma: (Boolean) -> Unit,
    onToggleMa: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        ControlMenuTrigger(
            label = "指标",
            expanded = expanded,
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(min = 220.dp)
        ) {
            IndicatorMenuItem(
                label = "成交量",
                description = "副图",
                checked = showVolume,
                enabled = showVolume || activeSubChartCount < 2,
                onCheckedChange = onToggleVolume
            )
            IndicatorMenuItem(
                label = "RSI",
                description = "副图",
                checked = showRsi,
                enabled = showRsi || activeSubChartCount < 2,
                onCheckedChange = onToggleRsi
            )
            IndicatorMenuItem(
                label = "MACD",
                description = "副图",
                checked = showMacd,
                enabled = showMacd || activeSubChartCount < 2,
                onCheckedChange = onToggleMacd
            )
            IndicatorMenuItem(
                label = "EMA",
                description = "主图叠加",
                checked = showEma,
                enabled = true,
                onCheckedChange = onToggleEma
            )
            IndicatorMenuItem(
                label = "MA",
                description = "主图叠加",
                checked = showMa,
                enabled = true,
                onCheckedChange = onToggleMa
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )
            Text(
                text = "副图指标最多同时显示 2 个",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ControlMenuTrigger(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing),
        label = "controlMenuArrow"
    )
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(arrowRotation)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (expanded) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
            trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = null,
        shape = RoundedCornerShape(AgentTheme.Shapes.full)
    )
}

@Composable
private fun IndicatorMenuItem(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    DropdownMenuItem(
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled
                )
            }
        }
    )
}

/**
 * 图表内容
 */
@Composable
private fun ChartContent(
    chartData: CandleChartData,
    showVolume: Boolean,
    showRsi: Boolean,
    showMacd: Boolean = true,
    showEma: Boolean = true,
    showMa: Boolean = false,
    showBoll: Boolean = false,
    onCandleHover: ((Int) -> Unit)? = null,
    isCompact: Boolean = false
) {
    val config = remember(showVolume, showRsi, showMacd, showEma, showMa, showBoll) {
        CandleChartConfig(
            showVolume = showVolume,
            showRsi = showRsi,
            showMacd = showMacd,
            showEma = showEma,
            showMa = showMa,
            showBoll = showBoll,
            mainChartHeight = 280,
            volumeChartHeight = 100,
            indicatorChartHeight = 110
        )
    }

    val chartPadding = if (isCompact) AgentTheme.Spacing.sm else AgentTheme.Spacing.lg

    CandleChartPanel(
        data = chartData,
        config = config,
        onCandleHover = onCandleHover,
        modifier = Modifier
            .fillMaxSize()
            .padding(chartPadding)
    )
}

/**
 * 当日行情网格
 */
@Composable
private fun DayQuoteGrid(stockInfo: StockInfo) {
    val amplitude = if (stockInfo.prevClose != 0f) {
        (stockInfo.dayHigh - stockInfo.dayLow) / stockInfo.prevClose * 100
    } else {
        0f
    }

    val items = listOf(
        Triple("今开", formatPriceRaw(stockInfo.openPrice), false),
        Triple("昨收", formatPriceRaw(stockInfo.prevClose), false),
        Triple("最高", formatPriceRaw(stockInfo.dayHigh), true),
        Triple("最低", formatPriceRaw(stockInfo.dayLow), false),
        Triple("振幅", "${formatPercentRaw(amplitude)}%", false),
        Triple("成交量", formatVolumeRaw(stockInfo.volume), false)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "当日行情",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3
        ) {
            items.forEach { (label, value, highlight) ->
                MetricItem(
                    label = label,
                    value = value,
                    highlight = highlight
                )
            }
        }
    }
}

/**
 * 基本面信息网格
 */
@Composable
private fun FundamentalGrid(stockInfo: StockInfo) {
    val items = buildList {
        stockInfo.peRatio?.let { add("市盈率 PE" to formatPriceRaw(it)) }
        stockInfo.pbRatio?.let { add("市净率 PB" to formatPriceRaw(it)) }
        add("总市值" to formatMarketCapRaw(stockInfo.marketCap))
        if (stockInfo.industry.isNotBlank()) add("行业" to stockInfo.industry)
        if (stockInfo.sector.isNotBlank()) add("板块" to stockInfo.sector)
        add("交易所" to stockInfo.exchange.name)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "基本面",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3
        ) {
            items.forEach { (label, value) ->
                MetricItem(
                    label = label,
                    value = value,
                    highlight = false
                )
            }
        }
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    highlight: Boolean
) {
    Column(
        modifier = Modifier.widthIn(min = 92.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 交易所徽章
 */
@Composable
private fun ExchangeBadge(
    exchange: Exchange,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (exchange) {
        Exchange.SH -> "上海" to MaterialTheme.colorScheme.primary
        Exchange.SZ -> "深圳" to MaterialTheme.colorScheme.secondary
        Exchange.BJ -> "北京" to MaterialTheme.colorScheme.tertiary
        Exchange.HK -> "香港" to MaterialTheme.colorScheme.outline
        Exchange.US -> "美股" to MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(AgentTheme.Shapes.extraSmall)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/**
 * 实时指示器
 */
@Composable
private fun LiveIndicator(
    isLive: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isLive) 1f else 0.7f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(1000),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "live_indicator"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    color = (if (isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        .copy(alpha = animatedAlpha),
                    shape = CircleShape
                )
        )
        Text(
            text = if (isLive) "实时行情" else "休市状态",
            style = MaterialTheme.typography.labelMedium,
            color = if (isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChangeBadge(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(AgentTheme.Shapes.full)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/**
 * 未选中状态
 */
@Composable
private fun EmptySelectionState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.lg)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(AgentTheme.Shapes.large)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CandlestickChart,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .padding(AgentTheme.Spacing.lg),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
            Text(
                text = "选择股票查看蜡烛图",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )
            Text(
                text = "从左侧面板中选择一只股票开始分析",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.56f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 加载中状态
 */
@Composable
private fun LoadingChartState(modifier: Modifier = Modifier, message: String? = null) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.md)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 3.dp
            )
            Text(
                text = message ?: "加载蜡烛图数据...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * 错误状态
 */
@Composable
private fun ErrorChartState(modifier: Modifier = Modifier, message: String? = null) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.md)
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            )
            Text(
                text = "加载失败",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = message ?: "请稍后重试",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}


