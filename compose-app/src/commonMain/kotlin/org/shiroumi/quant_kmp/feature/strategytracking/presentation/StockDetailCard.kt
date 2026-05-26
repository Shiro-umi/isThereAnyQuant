package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import model.candle.StockInfo
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode
import org.shiroumi.quant_kmp.ui.components.chart.CandleChartConfig
import org.shiroumi.quant_kmp.ui.components.chart.CandleChartPanel
import org.shiroumi.quant_kmp.ui.components.chart.CandleMarker
import org.shiroumi.quant_kmp.ui.components.chart.MarkerPosition
import org.shiroumi.quant_kmp.ui.theme.quantColors

private const val DetailBoundsDurationMs = 380
private val DetailChartHeight = 360.dp
private val DetailContentHeight = 560.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun StockDetailCard(
    anchor: TrackingOverlayAnchorState,
    detail: SelectedStockDetail?,
    sharedTransitionScope: SharedTransitionScope,
    sharedAnimatedVisibilityScope: AnimatedVisibilityScope,
    contentVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) = with(sharedTransitionScope) {
    val dismissSource = remember { MutableInteractionSource() }
    val cardSource = remember { MutableInteractionSource() }
    val selected = detail?.selected
    val resolvedNode = selected?.node ?: anchor.node
    val resolvedSection = selected?.section ?: anchor.section
    val resolvedTradeDate = selected?.tradeDate ?: anchor.tradeDate
    val cardKey = selected?.cardKey ?: anchor.cardKey
    val stockInfo = detail?.stockInfo
    val candleData = detail?.candleData
    val isLoading = detail?.isLoading ?: true
    val error = detail?.error

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
            .clickable(
                interactionSource = dismissSource,
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 40.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            modifier = Modifier
                .widthIn(min = 680.dp, max = 780.dp)
                .sharedElement(
                    sharedContentState = rememberSharedContentState(cardKey),
                    animatedVisibilityScope = sharedAnimatedVisibilityScope,
                    boundsTransform = { _, _ ->
                        tween(durationMillis = DetailBoundsDurationMs, easing = LinearOutSlowInEasing)
                    }
                )
                .clip(MaterialTheme.shapes.large)
                .clickable(
                    interactionSource = cardSource,
                    indication = null,
                    onClick = {}
                ),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StockDetailHeader(
                    node = resolvedNode,
                    cardKey = cardKey,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = sharedAnimatedVisibilityScope,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DetailContentHeight)
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = contentVisible,
                        enter = fadeIn(
                            animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)
                        ) + scaleIn(
                            animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing),
                            initialScale = 0.985f
                        ),
                        exit = fadeOut(animationSpec = tween(durationMillis = 90)),
                        label = "tracking_detail_content"
                    ) {
                        when {
                            isLoading -> CardLoadingContent()
                            error != null -> CardErrorContent(error = error)
                            else -> CardSuccessContent(
                                node = resolvedNode,
                                section = resolvedSection,
                                tradeDate = resolvedTradeDate,
                                stockInfo = stockInfo,
                                candleData = candleData,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun StockDetailHeader(
    node: StrategyTrackingStockNode,
    cardKey: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) = with(sharedTransitionScope) {
    val pnlRise = MaterialTheme.colorScheme.primary
    val pnlFall = MaterialTheme.colorScheme.tertiary

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = node.stockName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(trackingStockNameKey(cardKey)),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ ->
                        tween(durationMillis = DetailBoundsDurationMs, easing = LinearOutSlowInEasing)
                    }
                )
            )
            Text(
                text = node.stockCode,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(trackingStockCodeKey(cardKey)),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ ->
                        tween(durationMillis = DetailBoundsDurationMs, easing = LinearOutSlowInEasing)
                    }
                )
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            node.actualPnl?.let { pnl ->
                val color = if (pnl >= 0f) pnlRise else pnlFall
                val sign = if (pnl >= 0f) "+" else "-"
                Text(
                    text = "$sign${formatPnlValue(pnl)}%",
                    style = MaterialTheme.typography.headlineSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold
                )
            }
            node.maxPnl?.let { pnl ->
                val color = if (pnl >= 0f) pnlRise else pnlFall
                val sign = if (pnl >= 0f) "+" else "-"
                Text(
                    text = "最高 $sign${formatPnlValue(pnl)}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = color.copy(alpha = 0.65f)
                )
            }
        }
    }
}

@Composable
private fun CardLoadingContent() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "正在加载K线数据...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CardErrorContent(error: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        DetailChartSkeleton()
    }
}

@Composable
private fun CardSuccessContent(
    node: StrategyTrackingStockNode,
    section: StrategyTrackingSection,
    tradeDate: String,
    stockInfo: StockInfo?,
    candleData: model.candle.CandleChartData?,
) {
    val chartData = candleData ?: return
    val buyDate = node.buyDate
    val sellDate = if (section == StrategyTrackingSection.CLEARED) tradeDate else null

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        stockInfo?.let { info ->
            StockInfoGrid(info)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            buyDate?.let { buy ->
                MarkerLabel(
                    label = "买点: $buy",
                    color = MaterialTheme.quantColors.bullish
                )
            }
            sellDate?.let { sell ->
                MarkerLabel(
                    label = "卖点: $sell",
                    color = MaterialTheme.quantColors.bearish
                )
            }
        }

        val bullishColor = MaterialTheme.quantColors.bullish
        val bearishColor = MaterialTheme.quantColors.bearish
        val markers = remember(buyDate, sellDate, bullishColor, bearishColor) {
            mutableListOf<CandleMarker>().apply {
                if (buyDate != null) {
                    add(
                        CandleMarker(
                            date = buyDate,
                            label = "买",
                            color = bullishColor,
                            position = MarkerPosition.BELOW
                        )
                    )
                }
                if (sellDate != null) {
                    add(
                        CandleMarker(
                            date = sellDate,
                            label = "卖",
                            color = bearishColor,
                            position = MarkerPosition.ABOVE
                        )
                    )
                }
            }
        }

        val config = remember(chartData.candles.size) {
            CandleChartConfig(
                showVolume = true,
                showMacd = false,
                showRsi = false,
                showEma = false,
                showMa = false,
                showBoll = false,
                mainChartHeight = 240,
                volumeChartHeight = 48,
                defaultVisibleCandles = chartData.candles.size
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DetailChartHeight)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(8.dp)
        ) {
            CandleChartPanel(
                data = chartData,
                config = config,
                markers = markers,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun DetailChartSkeleton() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DetailChartHeight)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    )
}

@Composable
private fun StockInfoGrid(info: StockInfo) {
    val fields = remember(info) {
        buildList {
            add("交易所" to formatExchange(info.code))
            add("行业" to info.industry.ifBlank { "—" })
            add("板块" to info.sector.ifBlank { "—" })
            add("最新价" to formatPrice(info.latestPrice))
            add("涨跌额" to formatSignedPrice(info.changeAmount))
            add("涨跌幅" to formatSignedPercent(info.changePercent))
            add("开盘" to formatPrice(info.openPrice))
            add("昨收" to formatPrice(info.prevClose))
            add("最高" to formatPrice(info.dayHigh))
            add("最低" to formatPrice(info.dayLow))
            add("成交量" to formatVolume(info.volume))
            add("成交额" to formatTurnover(info.turnover))
            add("市值" to formatMarketCap(info.marketCap))
            add("PE" to info.peRatio?.let(::formatNumber).orEmpty().ifBlank { "—" })
            add("PB" to info.pbRatio?.let(::formatNumber).orEmpty().ifBlank { "—" })
            add("更新时间" to formatUpdateTime(info.updateTime))
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        fields.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { (label, value) ->
                    InfoItem(
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MarkerLabel(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatPnlValue(value: Float): String {
    val rounded = kotlin.math.round(kotlin.math.abs(value) * 100) / 100f
    val intPart = rounded.toInt()
    val decimal = kotlin.math.round((rounded - intPart) * 100).toInt()
    val decimalStr = if (decimal < 10) "0$decimal" else "$decimal"
    return "$intPart.$decimalStr"
}

private fun formatVolume(volume: Float): String = when {
    volume >= 1_0000_0000f -> "${(volume / 1_0000_0000f * 100).toLong() / 100f}亿"
    volume >= 1_0000f -> "${(volume / 1_0000f * 100).toLong() / 100f}万"
    else -> volume.toLong().toString()
}

private fun formatTurnover(turnover: Float): String = when {
    turnover >= 1_0000_0000f -> "${(turnover / 1_0000_0000f * 100).toLong() / 100f}亿"
    turnover >= 1_0000f -> "${(turnover / 1_0000f * 100).toLong() / 100f}万"
    else -> turnover.toLong().toString()
}

private fun formatMarketCap(cap: Float): String = when {
    cap >= 1_0000_0000f -> "${(cap / 1_0000_0000f * 100).toLong() / 100f}亿"
    cap >= 1_0000f -> "${(cap / 1_0000f * 100).toLong() / 100f}万"
    else -> cap.toLong().toString()
}

private fun formatNumber(value: Float): String {
    val rounded = kotlin.math.round(value * 100) / 100f
    val intPart = kotlin.math.abs(rounded).toInt()
    val decimal = kotlin.math.round((kotlin.math.abs(rounded) - intPart) * 100).toInt()
    val decimalStr = if (decimal < 10) "0$decimal" else "$decimal"
    return "$intPart.$decimalStr"
}

private fun formatPrice(value: Float): String = formatNumber(value)

private fun formatSignedPrice(value: Float): String {
    val sign = if (value >= 0f) "+" else "-"
    return sign + formatNumber(kotlin.math.abs(value))
}

private fun formatSignedPercent(value: Float): String {
    val sign = if (value >= 0f) "+" else "-"
    return "$sign${formatNumber(kotlin.math.abs(value))}%"
}

private fun formatExchange(code: String): String = code.substringAfter('.', "")
    .ifBlank { "—" }

private fun formatUpdateTime(updateTime: Long): String = runCatching {
    val local = Instant.fromEpochMilliseconds(updateTime)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val month = local.monthNumber.toString().padStart(2, '0')
    val day = local.dayOfMonth.toString().padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    "$month-$day $hour:$minute"
}.getOrDefault("—")
