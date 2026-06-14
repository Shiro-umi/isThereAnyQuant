package org.shiroumi.quant_kmp.ui.markdown.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import model.candle.CandleChartData
import model.candle.CandlePeriod
import model.ws.toCandle
import org.shiroumi.quant_kmp.ui.markdown.CandleTradePlanSpec
import org.shiroumi.quant_kmp.data.candle.toCandleChartData
import org.shiroumi.quant_kmp.service.GlobalWebSocketClient
import org.shiroumi.quant_kmp.ui.markdown.CandleMarkerSpec
import org.shiroumi.quant_kmp.ui.markdown.KLineBlockSpec
import org.shiroumi.quant_kmp.ui.components.chart.CandleChartConfig
import org.shiroumi.quant_kmp.ui.components.chart.CandleChartPanel
import org.shiroumi.quant_kmp.ui.components.chart.CandleMarker
import org.shiroumi.quant_kmp.ui.components.chart.CandleTradePlan
import org.shiroumi.quant_kmp.ui.components.chart.MarkerPosition
import org.shiroumi.quant_kmp.ui.components.chart.TradePlanSide
import org.shiroumi.quant_kmp.ui.markdown.QuantBlockWhitelist

private sealed class KLineLoadState {
    data object Loading : KLineLoadState()
    data class Success(val chartData: CandleChartData) : KLineLoadState()
    data class Error(val message: String) : KLineLoadState()
}

private val embeddedChartJson = Json { ignoreUnknownKeys = true }

/**
 * 嵌入 Markdown 的 K 线图表块。
 *
 * 根据 [spec] 通过 WebSocket 订阅行情数据，渲染可交互的 [CandleChartPanel]。
 * 自动处理加载/成功/错误三态，图表内容保持 16:9。
 */
@Composable
fun EmbeddedKLineChartBlock(
    spec: KLineBlockSpec,
    modifier: Modifier = Modifier
) {
    val period = remember(spec.period) { parsePeriod(spec.period) }
    var loadState by remember { mutableStateOf<KLineLoadState>(KLineLoadState.Loading) }
    var retryTrigger by remember { mutableIntStateOf(0) }

    // 离开报告块时清理订阅；实际订阅在加载 effect 中执行，便于重试重新发送请求。
    DisposableEffect(spec.tsCode, period, spec.startDate, spec.endDate) {
        onDispose {
            GlobalWebSocketClient.unsubscribeCandle(spec.tsCode, period)
        }
    }

    // 收数据：先启动等待，再发送订阅；超时后落入错误态，用户可手动重试。
    LaunchedEffect(spec.tsCode, period, spec.startDate, spec.endDate, retryTrigger) {
        loadState = KLineLoadState.Loading
        try {
            val eventDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                GlobalWebSocketClient.candleEventsFlow(spec.tsCode, period)
                    .first { e: GlobalWebSocketClient.CandleStreamEvent ->
                        e is GlobalWebSocketClient.CandleStreamEvent.Data ||
                            e is GlobalWebSocketClient.CandleStreamEvent.Error
                    }
            }
            GlobalWebSocketClient.subscribeCandle(
                tsCode = spec.tsCode,
                period = period,
                limit = spec.subscriptionLimit(period),
                startDate = spec.subscriptionStartDate(period),
                endDate = spec.endDate
            )
            when (val event = withTimeout(30_000L) { eventDeferred.await() }) {
                is GlobalWebSocketClient.CandleStreamEvent.Data -> {
                    val payload = event.decode(embeddedChartJson)
                    val candles = payload.candles.map { it.toCandle(spec.tsCode) }
                    val chartData = candles
                        .toCandleChartData(spec.tsCode, spec.tsCode)
                        .windowForSpec(spec, period)
                    loadState = KLineLoadState.Success(chartData)
                }
                is GlobalWebSocketClient.CandleStreamEvent.Error -> {
                    loadState = KLineLoadState.Error(event.payload.message)
                }
            }
        } catch (_: TimeoutCancellationException) {
            loadState = KLineLoadState.Error("K线数据加载超时，请重试")
        } catch (e: Exception) {
            loadState = KLineLoadState.Error(e.message ?: "K线数据加载超时，请重试")
        }
    }

    // 图表标记点颜色
    val buyColor = MaterialTheme.colorScheme.primary
    val sellColor = MaterialTheme.colorScheme.error

    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            KLineBlockHeader(spec = spec, period = period, buyColor = buyColor, sellColor = sellColor)

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val heightDp = (maxWidth * 0.68f).coerceIn(320.dp, 520.dp)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heightDp)
                ) {
                    when (val state = loadState) {
                        is KLineLoadState.Loading -> KLineSkeleton()
                        is KLineLoadState.Success -> {
                            val config = remember(state.chartData, spec.indicators, heightDp) {
                                buildConfig(spec, state.chartData, heightDp.value.toInt())
                            }
                            val markers = remember(spec.markers, buyColor, sellColor) {
                                spec.markers?.mapNotNull { it.toCandleMarker(buyColor, sellColor) }
                                    ?: emptyList()
                            }
                            val tradePlan = remember(spec.tradePlan) { spec.tradePlan?.toCandleTradePlan() }
                            CandleChartPanel(
                                data = state.chartData,
                                config = config,
                                markers = markers,
                                tradePlan = tradePlan,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        is KLineLoadState.Error -> KLineError(
                            message = state.message,
                            onRetry = { retryTrigger++ }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KLineBlockHeader(
    spec: KLineBlockSpec,
    period: CandlePeriod,
    buyColor: Color,
    sellColor: Color
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val variantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val indicatorColor = MaterialTheme.colorScheme.secondary

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "${period.displayName()}周期 K 线",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
                Text(
                    text = "${spec.tsCode} · ${spec.startDate} 至 ${spec.endDate}",
                    style = MaterialTheme.typography.labelSmall,
                    color = variantColor
                )
            }
            spec.maxCandles?.let { maxCandles ->
                Text(
                    text = "局部 ${maxCandles} 根",
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = variantColor
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderLegendItem(color = buyColor, label = "涨")
            HeaderLegendItem(color = sellColor, label = "跌")
            if (spec.indicators == null || spec.indicators.contains("MA20")) {
                HeaderLegendItem(color = indicatorColor, label = "MA20", line = true)
            }
            if (!spec.markers.isNullOrEmpty()) {
                HeaderLegendItem(color = buyColor, label = "买点")
                HeaderLegendItem(color = sellColor, label = "卖点")
            }
        }
    }
}

@Composable
private fun HeaderLegendItem(
    color: Color,
    label: String,
    line: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(if (line) 16.dp else 8.dp)
                .height(if (line) 2.dp else 8.dp)
                .background(color, RoundedCornerShape(1.dp))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== 骨架屏 ====================

@Composable
private fun KLineSkeleton() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.small)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "加载 K 线数据…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

// ==================== 错误占位 ====================

@Composable
private fun KLineError(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.small)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "图表加载失败",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onRetry) {
                Text(
                    text = "重试",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ==================== 工具函数 ====================

private fun parsePeriod(period: String): CandlePeriod = when (period) {
    "MIN_5" -> CandlePeriod.MIN_5
    "MIN_15" -> CandlePeriod.MIN_15
    "MIN_30" -> CandlePeriod.MIN_30
    "MIN_60" -> CandlePeriod.MIN_60
    "WEEK" -> CandlePeriod.WEEK
    "MONTH" -> CandlePeriod.MONTH
    else -> CandlePeriod.DAY
}

private fun buildConfig(
    spec: KLineBlockSpec,
    chartData: CandleChartData,
    containerHeight: Int
): CandleChartConfig {
    val indicators = spec.indicators
    val showVolume = indicators == null || indicators.contains("VOLUME")
    val showMacd = indicators == null || indicators.contains("MACD")
    val showRsi = indicators == null || indicators.contains("RSI")
    val showEma = indicators == null || indicators.contains("EMA20")
    val showMa = indicators == null || indicators.contains("MA20")
    val showBoll = indicators == null || indicators.contains("BOLL")

    return CandleChartConfig(
        showVolume = showVolume,
        showMacd = showMacd,
        showRsi = showRsi,
        showEma = showEma,
        showMa = showMa,
        showBoll = showBoll,
        mainChartHeight = (containerHeight * 0.58f).toInt().coerceAtLeast(160),
        volumeChartHeight = if (showVolume) (containerHeight * 0.12f).toInt().coerceAtLeast(44) else 0,
        indicatorChartHeight = if (showMacd || showRsi) (containerHeight * 0.16f).toInt().coerceAtLeast(56) else 0,
        defaultVisibleCandles = chartData.candles.size,
        interactive = false,
        showLegend = false,
        showMarkerDots = false
    )
}

private fun CandleMarkerSpec.toCandleMarker(
    buyColor: androidx.compose.ui.graphics.Color,
    sellColor: androidx.compose.ui.graphics.Color
): CandleMarker? {
    val color = when (type) {
        "BUY" -> buyColor
        "SELL" -> sellColor
        else -> return null
    }
    return CandleMarker(
        date = date,
        label = label,
        color = color,
        position = if (type == "BUY") MarkerPosition.BELOW else MarkerPosition.ABOVE,
        price = price
    )
}

private fun CandleTradePlanSpec.toCandleTradePlan(): CandleTradePlan =
    CandleTradePlan(
        side = if (side == "SELL") TradePlanSide.SELL else TradePlanSide.BUY,
        entryPrice = entryPrice,
        stopLossPrice = stopLossPrice,
        targetPrice = targetPrice,
        riskRewardRatio = riskRewardRatio,
        entryLabel = entryLabel ?: if (side == "SELL") "卖点" else "买点",
        stopLabel = stopLabel ?: "止损",
        targetLabel = targetLabel ?: "目标"
    )

private fun CandleChartData.windowForSpec(
    spec: KLineBlockSpec,
    period: CandlePeriod
): CandleChartData {
    if (candles.isEmpty()) return this

    val limit = spec.maxCandles ?: if (period.isIntraday && spec.hasTradeAnnotations()) {
        QuantBlockWhitelist.MAX_VISIBLE_CANDLES
    } else {
        candles.size
    }
    val maxCandles = limit.coerceIn(1, candles.size)
    if (candles.size <= maxCandles) return this

    val focus = spec.focusDate
        ?: spec.markers?.firstOrNull()?.date
        ?: candles.last().date
    val focusIndex = candles.indexOfFirst { it.date.matchesFocusDate(focus) }
        .takeIf { it >= 0 }
        ?: candles.lastIndex
    val before = (maxCandles * 2) / 3
    val rawStart = focusIndex - before
    val start = rawStart.coerceIn(0, (candles.size - maxCandles).coerceAtLeast(0))
    val endExclusive = (start + maxCandles).coerceAtMost(candles.size)

    return slice(start, endExclusive)
}

private fun KLineBlockSpec.hasTradeAnnotations(): Boolean =
    !markers.isNullOrEmpty() || tradePlan != null

private fun KLineBlockSpec.subscriptionLimit(period: CandlePeriod): Int? =
    if (period.isIntraday && hasTradeAnnotations()) {
        ((maxCandles ?: QuantBlockWhitelist.MAX_VISIBLE_CANDLES) + 80).coerceAtLeast(140)
    } else {
        null
    }

private fun KLineBlockSpec.subscriptionStartDate(period: CandlePeriod): String? =
    if (period.isIntraday && hasTradeAnnotations()) null else startDate

private val CandlePeriod.isIntraday: Boolean
    get() = when (this) {
        CandlePeriod.MIN_5, CandlePeriod.MIN_15, CandlePeriod.MIN_30, CandlePeriod.MIN_60 -> true
        CandlePeriod.DAY, CandlePeriod.WEEK, CandlePeriod.MONTH -> false
    }

private fun CandlePeriod.displayName(): String = when (this) {
    CandlePeriod.MIN_5 -> "5分钟"
    CandlePeriod.MIN_15 -> "15分钟"
    CandlePeriod.MIN_30 -> "30分钟"
    CandlePeriod.MIN_60 -> "60分钟"
    CandlePeriod.DAY -> "日"
    CandlePeriod.WEEK -> "周"
    CandlePeriod.MONTH -> "月"
}

private fun CandleChartData.slice(start: Int, endExclusive: Int): CandleChartData =
    copy(
        candles = candles.subList(start, endExclusive),
        volumes = volumes.safeSubList(start, endExclusive),
        ema20 = ema20.safeSubList(start, endExclusive),
        rsi6 = rsi6.safeSubList(start, endExclusive),
        macdDif = macdDif.safeSubList(start, endExclusive),
        macdDea = macdDea.safeSubList(start, endExclusive),
        macdBar = macdBar.safeSubList(start, endExclusive),
        ema5 = ema5?.safeSubList(start, endExclusive),
        ema10 = ema10?.safeSubList(start, endExclusive),
        ema60 = ema60?.safeSubList(start, endExclusive),
        rsi12 = rsi12?.safeSubList(start, endExclusive),
        rsi24 = rsi24?.safeSubList(start, endExclusive),
        ma5 = ma5?.safeSubList(start, endExclusive),
        ma10 = ma10?.safeSubList(start, endExclusive),
        ma20 = ma20?.safeSubList(start, endExclusive),
        ma60 = ma60?.safeSubList(start, endExclusive),
        bollUpper = bollUpper?.safeSubList(start, endExclusive),
        bollMid = bollMid?.safeSubList(start, endExclusive),
        bollLower = bollLower?.safeSubList(start, endExclusive)
    )

private fun <T> List<T>.safeSubList(start: Int, endExclusive: Int): List<T> {
    if (isEmpty()) return emptyList()
    val safeStart = start.coerceIn(0, size)
    val safeEnd = endExclusive.coerceIn(safeStart, size)
    return subList(safeStart, safeEnd)
}

private fun String.matchesFocusDate(focus: String): Boolean {
    if (this == focus || startsWith(focus) || focus.startsWith(this)) return true
    val candleMinute = take(16)
    val focusMinute = focus.take(16)
    if (candleMinute == focusMinute || candleMinute.endsWith(focusMinute)) return true
    if (length >= 16 && focus.length >= 11 && substring(5, 16) == focus.take(11)) return true
    return focus.length >= 5 && length >= 10 && substring(5, 10) == focus.take(5)
}
