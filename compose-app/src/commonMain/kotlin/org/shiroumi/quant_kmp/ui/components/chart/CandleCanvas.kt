package org.shiroumi.quant_kmp.ui.components.chart

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.candle.CandleChartData
import model.candle.CandleData
import model.candle.StockKLineInfo
import model.strategy.StockChartData
import model.toCandleChartData
import org.shiroumi.quant_kmp.ui.utils.formatPrice
import org.shiroumi.quant_kmp.ui.theme.quantColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

// ==================== 时间标签格式化 ====================

/**
 * 将 K 线 date 字段格式化为 x 轴显示标签。
 *
 * - 日线/周线/月线：date = "2024-01-02"，显示 "01-02"
 * - 分钟线：date = "2024-01-02 09:30:00"，显示 "09:30"
 * - 特殊处理：时间部分为 "00:00" 时视为日线，显示月日
 */
private fun formatCandleDateLabel(date: String): String {
    val spaceIdx = date.indexOf(' ')
    return if (spaceIdx > 0) {
        val timePart = date.substring(spaceIdx + 1).take(5)
        // 时间为 "00:00" 说明这是日线数据（带了冗余时间部分），降级显示月日
        if (timePart == "00:00") {
            if (date.length >= 10) date.substring(5, 10) else date
        } else {
            timePart
        }
    } else {
        // 纯日期格式：取月日部分
        if (date.length >= 10) date.substring(5, 10) else date
    }
}

// ==================== 字号辅助 ====================

/**
 * 图表专用字号：在基础 sp 之上叠加平台补偿量。
 * JS 平台自动额外加 [chartFontAddition] sp，其他平台不变。
 */
private fun chartSp(base: Int): TextUnit = (base + chartFontAddition).sp

private const val PRICE_AXIS_MIN_WIDTH_PX = 24f
private const val PRICE_AXIS_LABEL_GAP_PX = 1f

// ==================== 指标类型 ====================

/**
 * 技术指标类型
 */
enum class IndicatorType(val displayName: String) {
    EMA("EMA"),
    MA("MA"),
    MACD("MACD"),
    BOLL("BOLL"),
    RSI("RSI"),
    VOLUME("成交量")
}

// ==================== 配置 ====================

/**
 * K线图显示配置
 */
@Stable
class CandleChartConfig(
    val showVolume: Boolean = true,
    val showMacd: Boolean = true,
    val showRsi: Boolean = false,
    val showBoll: Boolean = false,
    val showEma: Boolean = true,
    val showMa: Boolean = false,
    val mainChartHeight: Int = 280,
    val volumeChartHeight: Int = 60,
    val indicatorChartHeight: Int = 80,
    val maxScale: Float = 5f,
    val minScale: Float = 0.5f,
    val defaultVisibleCandles: Int = 80,
    val interactive: Boolean = true,
    val showLegend: Boolean = true,
    val showMarkerDots: Boolean = true
)

/**
 * K线标记点（用于标记买点/卖点等关键位置）
 */
data class CandleMarker(
    val date: String,
    val label: String,
    val color: Color,
    val position: MarkerPosition = MarkerPosition.ABOVE,
    val price: Float? = null
)

enum class MarkerPosition {
    ABOVE, BELOW
}

data class CandleTradePlan(
    val side: TradePlanSide = TradePlanSide.BUY,
    val entryPrice: Float,
    val stopLossPrice: Float,
    val targetPrice: Float,
    val riskRewardRatio: Float? = null,
    val entryLabel: String = "入场",
    val stopLabel: String = "止损",
    val targetLabel: String = "目标"
)

enum class TradePlanSide {
    BUY, SELL
}

private data class ChartAnnotationLabel(
    val text: String,
    val color: Color,
    val anchorY: Float
)

// ==================== 十字星状态抽象 ====================

/**
 * 十字星共享状态
 *
 * 持有当前 hover 的 K 线全局索引（-1 = 无）以及鼠标/手指的像素 Y 坐标。
 * 由主图创建并写入，所有副图只读使用，实现跨图表同步。
 */
@Stable
class CrosshairState {
    /** 当前 hover 的 K 线全局索引，-1 表示无 hover */
    var hoverIndex: Int by mutableIntStateOf(-1)
        internal set

    /** 鼠标/手指在主图中的像素 Y 位置，-1 表示无效 */
    var hoverY: Float by mutableFloatStateOf(-1f)
        internal set

    /** 是否处于激活状态 */
    val isActive: Boolean get() = hoverIndex >= 0
}

/** 创建并记住一个 [CrosshairState] */
@Composable
fun rememberCrosshairState(): CrosshairState = remember { CrosshairState() }

// ==================== DrawScope 十字星扩展 ====================

/**
 * 副图十字星 —— 仅绘制竖向虚线
 *
 * @param visibleIndex 在当前可见窗口中的索引（= hoverIndex - startIndex）
 * @param spacing      每列像素宽度
 * @param chartHeightPx 图表有效高度（px）
 * @param color        线条颜色
 */
fun DrawScope.drawCrosshairVertical(
    visibleIndex: Int,
    spacing: Float,
    chartHeightPx: Float,
    color: Color,
    chartLeft: Float = 0f
) {
    if (visibleIndex < 0) return
    val x = chartLeft + visibleIndex.toFloat() * spacing + spacing / 2f
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, chartHeightPx),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
    )
}

/**
 * 主图完整十字星 —— 竖线 + 横线 + 右侧价格气泡 + 底部日期气泡
 *
 * @param visibleIndex  在当前可见窗口中的索引（= hoverIndex - startIndex）
 * @param spacing       每列像素宽度
 * @param hoverY        鼠标/手指像素 Y 坐标（来自 [CrosshairState]）
 * @param hoverDate     对应 K 线的日期字符串（用于底部气泡）
 * @param chartWidth    图表有效宽度（不含右侧标签区）
 * @param chartHeightPx 图表有效高度（px）
 * @param priceMin      Y 轴价格最小值
 * @param priceRangeVal Y 轴价格区间（max - min）
 * @param crosshairColor 十字线颜色
 * @param labelBgColor   气泡背景色
 * @param labelTextColor 气泡文字颜色
 * @param textMeasurer  文字测量器（从外部传入避免 Composable 限制）
 */
fun DrawScope.drawCrosshairFull(
    visibleIndex: Int,
    spacing: Float,
    hoverY: Float,
    hoverDate: String,
    chartLeft: Float,
    chartWidth: Float,
    chartHeightPx: Float,
    priceMin: Float,
    priceRangeVal: Float,
    crosshairColor: Color,
    labelBgColor: Color,
    labelTextColor: Color,
    textMeasurer: TextMeasurer
) {
    if (visibleIndex < 0) return
    val crossX = chartLeft + visibleIndex.toFloat() * spacing + spacing / 2f

    // 竖线（复用副图方法）
    drawCrosshairVertical(visibleIndex, spacing, chartHeightPx, crosshairColor, chartLeft)

    // 横线 + 右侧价格气泡（仅当 hoverY 在图表范围内时）
    if (hoverY in 0f..chartHeightPx) {
        drawLine(
            color = crosshairColor,
            start = Offset(chartLeft, hoverY),
            end = Offset(chartLeft + chartWidth, hoverY),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
        )

        // 价格气泡
        val hoverPrice = priceMin + (1f - hoverY / chartHeightPx) * priceRangeVal
        val priceText = hoverPrice.formatPrice()
        val priceTm = textMeasurer.measure(
            priceText,
            style = TextStyle(color = labelTextColor, fontSize = chartSp(9), fontWeight = FontWeight.SemiBold)
        )
        val labelW = priceTm.size.width + 8f
        val labelH = priceTm.size.height + 4f
        val labelX = (size.width - labelW).coerceAtLeast(chartLeft + chartWidth + PRICE_AXIS_LABEL_GAP_PX)
        drawRect(
            color = labelBgColor,
            topLeft = Offset(labelX, hoverY - labelH / 2),
            size = Size(labelW, labelH)
        )
        drawText(
            textMeasurer = textMeasurer,
            text = priceText,
            style = TextStyle(color = labelTextColor, fontSize = chartSp(9), fontWeight = FontWeight.SemiBold),
            topLeft = Offset(labelX + 4f, hoverY - priceTm.size.height / 2)
        )
    }

    // 底部日期气泡
    val dateText = formatCandleDateLabel(hoverDate)
    val dateTm = textMeasurer.measure(
        dateText,
        style = TextStyle(color = labelTextColor, fontSize = chartSp(9))
    )
    val dateW = dateTm.size.width + 8f
    val dateH = dateTm.size.height + 4f
    drawRect(
        color = labelBgColor,
        topLeft = Offset(crossX - dateW / 2, chartHeightPx + 2f),
        size = Size(dateW, dateH)
    )
    drawText(
        textMeasurer = textMeasurer,
        text = dateText,
        style = TextStyle(color = labelTextColor, fontSize = chartSp(9)),
        topLeft = Offset(crossX - dateTm.size.width / 2, chartHeightPx + 4f)
    )
}

// ==================== 主图面板 ====================

/**
 * K线图Canvas组件
 *
 * 功能特性：
 * - 左右滑动查看历史数据
 * - 捏合手势缩放
 * - 实时十字准星（主图完整 + 副图竖线同步）
 * - hover同步更新数据到标题抽屉
 * - 支持切换多种技术指标
 *
 * @param data K线数据
 * @param config 图表配置
 * @param modifier 修饰符
 * @param onCandleHover K线hover回调，参数为当前hover的K线索引（-1表示没有hover）
 */
@Composable
fun CandleChartPanel(
    data: CandleChartData,
    config: CandleChartConfig = remember { CandleChartConfig() },
    modifier: Modifier = Modifier,
    onCandleHover: ((Int) -> Unit)? = null,
    markers: List<CandleMarker> = emptyList(),
    tradePlan: CandleTradePlan? = null
) {
    if (data.candles.isEmpty()) {
        EmptyChartPanel(modifier = modifier)
        return
    }

    val candles = data.candles
    val colorScheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.quantColors

    // 涨跌配色：A 股惯例红涨绿跌，独立于主题 primary 色相
    val upColor = semantic.bullish
    val downColor = semantic.bearish
    val emaColor = colorScheme.secondary
    val maColor = colorScheme.tertiary
    val bollUpperColor = colorScheme.tertiary.copy(alpha = 0.8f)
    val bollLowerColor = colorScheme.tertiary.copy(alpha = 0.8f)
    val bollMidColor = colorScheme.secondary
    val macdPositiveColor = upColor.copy(alpha = 0.85f)
    val macdNegativeColor = downColor.copy(alpha = 0.85f)
    val rsiColor = colorScheme.primary
    val gridColor = colorScheme.outline.copy(alpha = 0.15f)
    val textColor = colorScheme.onSurfaceVariant
    val crosshairColor = colorScheme.onSurface.copy(alpha = 0.45f)
    val labelBgColor = colorScheme.surfaceContainerHighest
    val labelTextColor = colorScheme.onSurface
    val annotationLabelBgColor = colorScheme.surfaceContainerHighest

    // 交互状态
    // scale：缩放比例（1 = defaultVisibleCandles 根，>1 放大显示更少根，<1 缩小显示更多根）
    var scale by remember { mutableFloatStateOf(1f) }
    // endIndexOffset：视口右边界距末尾的偏移条数（0 = 最新K线在右边，正数 = 向历史方向滚动）
    var endIndexOffset by remember { mutableIntStateOf(0) }
    var selectedIndex by remember { mutableIntStateOf(-1) }

    // 十字星共享状态（由主图写入，副图只读）
    val crosshairState = rememberCrosshairState()

    // 计算可见K线数量（scale 越大看到越少）
    val visibleCandles = (config.defaultVisibleCandles / scale).toInt()
        .coerceIn(kotlin.math.min(10, candles.size).coerceAtLeast(1), candles.size)

    // 计算当前可见范围（endIndexOffset 直接以条目数为单位，无需单位换算）
    val endIndex = (candles.size - 1 - endIndexOffset)
        .coerceIn(visibleCandles - 1, candles.size - 1)
    val startIndex = (endIndex - visibleCandles + 1).coerceAtLeast(0)

    // 用 State 引用暴露给 pointerInput，避免每次重组时 key 变化导致协程重启（滚轮跳变）
    val visibleCandlesState = rememberUpdatedState(visibleCandles)
    val endIndexOffsetState = rememberUpdatedState(endIndexOffset)
    val startIndexState = rememberUpdatedState(startIndex)

    val visibleCandlesList = candles.subList(startIndex, endIndex + 1)

    // 计算价格范围（包含指标）
    val priceRange = remember(visibleCandlesList, config, tradePlan, markers) {
        calculatePriceRange(visibleCandlesList, data, config, tradePlan, markers)
    }
    val textMeasurer = rememberTextMeasurer()
    val priceAxisTextStyle = TextStyle(color = textColor, fontSize = chartSp(9))
    val priceAxisWidthPx = remember(priceRange, priceAxisTextStyle, textMeasurer) {
        calculatePriceAxisWidth(
            priceRange = priceRange,
            textMeasurer = textMeasurer,
            textStyle = priceAxisTextStyle
        )
    }

    // 动画效果
    val animatedScale by animateFloatAsState(targetValue = scale)

    // 触发hover回调
    LaunchedEffect(crosshairState.hoverIndex) {
        onCandleHover?.invoke(crosshairState.hoverIndex)
    }

    val interactionModifier = if (config.interactive) {
        Modifier
            .pointerInput(Unit) {
                var panAccum = 0f
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(config.minScale, config.maxScale)
                    // 触控板双指横向平移：亚像素累积后转为条目偏移
                    val curVisible = visibleCandlesState.value
                    val candleWidthPx = (size.width - priceAxisWidthPx * 2f).coerceAtLeast(1f) / curVisible
                    panAccum -= pan.x   // 向右滑 pan.x>0，endIndexOffset 减小（向最新）
                    val steps = (panAccum / candleWidthPx).toInt()
                    if (steps != 0) {
                        panAccum -= steps * candleWidthPx
                        endIndexOffset = (endIndexOffset + steps)
                            .coerceIn(0, candles.size - curVisible)
                    }
                }
            }
            .pointerInput(Unit) {
                var dragAccum = 0f
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val curVisible = visibleCandlesState.value
                    val candleWidthPx = (size.width - priceAxisWidthPx * 2f).coerceAtLeast(1f) / curVisible
                    dragAccum -= dragAmount.x   // 向右拖 dragAmount.x>0，endIndexOffset 减小
                    val steps = (dragAccum / candleWidthPx).toInt()
                    if (steps != 0) {
                        dragAccum -= steps * candleWidthPx
                        endIndexOffset = (endIndexOffset + steps)
                            .coerceIn(0, candles.size - curVisible)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    val curVisible = visibleCandlesState.value
                    val curStart = startIndexState.value
                    val chartLeft = priceAxisWidthPx
                    val chartWidth = (size.width - priceAxisWidthPx * 2f).coerceAtLeast(1f)
                    val candleWidth = chartWidth / curVisible
                    val clickedIndex = curStart + ((offset.x - chartLeft) / candleWidth).toInt()
                    if (clickedIndex in candles.indices) {
                        selectedIndex = clickedIndex
                    }
                })
            }
            // 鼠标/触屏 move + 滚轮交互
            // key 固定为 Unit，通过 rememberUpdatedState 读取最新值，避免协程重启跳变
            .pointerInput(Unit) {
                // 触控板亚像素累积：touchpad 每次事件 scrollDelta 可能是小数，
                // 累积到超过 1 条时才实际移动，消除整数截断导致的卡顿/跳变
                var scrollAccum = 0f
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val curVisible = visibleCandlesState.value
                        val curEndOffset = endIndexOffsetState.value
                        val curStart = (candles.size - 1 - curEndOffset - curVisible + 1).coerceAtLeast(0)
                        when (event.type) {
                            PointerEventType.Move,
                            PointerEventType.Press -> {
                                val position = event.changes.firstOrNull()?.position
                                if (position != null) {
                                    val chartLeft = priceAxisWidthPx
                                    val chartWidth = (size.width - priceAxisWidthPx * 2f).coerceAtLeast(1f)
                                    val candleWidth = chartWidth / curVisible
                                    val index = curStart + ((position.x - chartLeft) / candleWidth).toInt()
                                    crosshairState.hoverIndex = if (index in candles.indices) index else -1
                                    crosshairState.hoverY = position.y
                                }
                            }
                            PointerEventType.Exit,
                            PointerEventType.Release -> {
                                crosshairState.hoverIndex = -1
                                crosshairState.hoverY = -1f
                            }
                            PointerEventType.Scroll -> {
                                val scrollDelta = event.changes.firstOrNull()?.scrollDelta ?: continue
                                val isCtrl = event.keyboardModifiers.isCtrlPressed
                                if (isCtrl) {
                                    // Ctrl + 滚轮：缩放
                                    val zoomFactor = if (scrollDelta.y < 0) 1.1f else 0.9f
                                    scale = (scale * zoomFactor).coerceIn(config.minScale, config.maxScale)
                                    scrollAccum = 0f
                                } else {
                                    // 触控板横向滚动：每次滚动 3 根K线的等效量，累积后整数化
                                    scrollAccum += scrollDelta.y * 3f
                                    val steps = scrollAccum.toInt()
                                    if (steps != 0) {
                                        scrollAccum -= steps
                                        endIndexOffset = (curEndOffset + steps)
                                            .coerceIn(0, candles.size - curVisible)
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
    } else {
        Modifier
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 主K线图（填充剩余空间）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(interactionModifier)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val chartLeft = priceAxisWidthPx
                val chartWidth = (size.width - priceAxisWidthPx * 2f).coerceAtLeast(1f)
                val chartHeightPx = size.height - 40f
                val candleSpacing = chartWidth / visibleCandles
                val candleWidth = candleSpacing * 0.7f

                // 绘制网格
                drawGridLines(chartLeft, chartWidth, chartHeightPx, gridColor)

                // 绘制K线
                visibleCandlesList.forEachIndexed { index, candle ->
                    val x = chartLeft + index.toFloat() * candleSpacing + candleSpacing / 2f
                    val openY = chartHeightPx - (candle.open - priceRange.min) / priceRange.range * chartHeightPx
                    val closeY = chartHeightPx - (candle.close - priceRange.min) / priceRange.range * chartHeightPx
                    val highY = chartHeightPx - (candle.high - priceRange.min) / priceRange.range * chartHeightPx
                    val lowY = chartHeightPx - (candle.low - priceRange.min) / priceRange.range * chartHeightPx

                    val isUp = candle.close >= candle.open
                    val color = if (isUp) upColor else downColor

                    val bodyTop = minOf(openY, closeY)
                    val bodyBottom = maxOf(openY, closeY)
                    val bodyHeight = maxOf(bodyBottom - bodyTop, 1f)

                    if (highY < bodyTop) drawLine(color, Offset(x, highY), Offset(x, bodyTop), strokeWidth = 1f)
                    if (lowY > bodyBottom) drawLine(color, Offset(x, bodyBottom), Offset(x, lowY), strokeWidth = 1f)

                    if (isUp) {
                        drawRect(color = color, topLeft = Offset(x - candleWidth / 2, bodyTop),
                            size = Size(candleWidth, bodyHeight), style = Stroke(width = 1.5f))
                    } else {
                        drawRect(color = color, topLeft = Offset(x - candleWidth / 2, bodyTop),
                            size = Size(candleWidth, bodyHeight))
                    }
                }

                // EMA线
                if (config.showEma) {
                    drawIndicatorLine(visibleCandlesList, data.ema20.subList(startIndex, endIndex + 1),
                        priceRange, chartLeft, chartWidth, chartHeightPx, emaColor, 1.5f)
                }

                // MA线
                if (config.showMa) {
                    drawIndicatorLine(visibleCandlesList,
                        data.ma20?.subList(startIndex, endIndex + 1) ?: List(visibleCandles) { null },
                        priceRange, chartLeft, chartWidth, chartHeightPx, maColor, 1.5f)
                }

                // 布林带
                if (config.showBoll) {
                    drawBollingerBands(visibleCandlesList, data, startIndex, endIndex,
                        priceRange, chartLeft, chartWidth, chartHeightPx, bollUpperColor, bollMidColor, bollLowerColor)
                }

                tradePlan?.let {
                    drawTradePlan(
                        plan = it,
                        priceRange = priceRange,
                        chartLeft = chartLeft,
                        chartWidth = chartWidth,
                        chartHeightPx = chartHeightPx,
                        profitColor = upColor,
                        lossColor = downColor
                    )
                }

                val annotationLabels = mutableListOf<ChartAnnotationLabel>()
                fun yOfPrice(price: Float): Float =
                    chartHeightPx - (price - priceRange.min) / priceRange.range * chartHeightPx
                val mergedEntryMarker = tradePlan?.let { plan ->
                    markers.firstOrNull { marker ->
                        marker.price?.isSamePrice(plan.entryPrice) == true &&
                            ((plan.side == TradePlanSide.BUY && marker.position == MarkerPosition.BELOW) ||
                                (plan.side == TradePlanSide.SELL && marker.position == MarkerPosition.ABOVE))
                    }
                }
                tradePlan?.let { plan ->
                    val ratioSuffix = plan.riskRewardRatio?.let {
                        val ratio = (it * 100).toLong() / 100.0
                        "  R:R $ratio"
                    } ?: ""
                    val entryLabel = mergedEntryMarker?.label ?: plan.entryLabel
                    annotationLabels.add(
                        ChartAnnotationLabel(
                            text = "${plan.targetLabel} ${plan.targetPrice.formatPrice()}",
                            color = upColor,
                            anchorY = yOfPrice(plan.targetPrice)
                        )
                    )
                    annotationLabels.add(
                        ChartAnnotationLabel(
                            text = "$entryLabel ${plan.entryPrice.formatPrice()}$ratioSuffix",
                            color = upColor,
                            anchorY = yOfPrice(plan.entryPrice)
                        )
                    )
                    annotationLabels.add(
                        ChartAnnotationLabel(
                            text = "${plan.stopLabel} ${plan.stopLossPrice.formatPrice()}",
                            color = downColor,
                            anchorY = yOfPrice(plan.stopLossPrice)
                        )
                    )
                }
                markers.forEach { marker ->
                    if (marker == mergedEntryMarker) return@forEach
                    val markerGlobalIndex = candles.findMarkerIndex(marker.date)
                    if (markerGlobalIndex in startIndex..endIndex) {
                        val candle = candles[markerGlobalIndex]
                        val anchorPrice = marker.price ?: if (marker.position == MarkerPosition.ABOVE) {
                            candle.high
                        } else {
                            candle.low
                        }
                        annotationLabels.add(
                            ChartAnnotationLabel(
                                text = marker.price?.let { "${marker.label} ${it.formatPrice()}" } ?: marker.label,
                                color = marker.color,
                                anchorY = yOfPrice(anchorPrice)
                            )
                        )
                    }
                }
                drawAnnotationLabels(
                    labels = annotationLabels,
                    chartLeft = chartLeft,
                    chartHeightPx = chartHeightPx,
                    backgroundColor = annotationLabelBgColor,
                    textMeasurer = textMeasurer
                )

                // 右侧价格标签
                for (i in 0..4) {
                    val price = priceRange.min + priceRange.range * i / 4
                    val y = chartHeightPx - i * chartHeightPx / 4
                    val priceText = price.formatPrice()
                    val priceLayout = textMeasurer.measure(
                        priceText,
                        style = priceAxisTextStyle
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = priceText,
                        style = priceAxisTextStyle,
                        topLeft = Offset(size.width - priceLayout.size.width, y - 5f)
                    )
                }

                // 底部时间标签
                val timeStep = max(1, visibleCandles / 5)
                for (i in 0 until visibleCandles step timeStep) {
                    val candle = visibleCandlesList[i]
                    val x = chartLeft + i.toFloat() * candleSpacing + candleSpacing / 2f
                    drawText(textMeasurer, formatCandleDateLabel(candle.date),
                        style = TextStyle(color = textColor, fontSize = chartSp(8)),
                        topLeft = Offset(x - 15f, chartHeightPx + 5f))
                }

                if (config.showMarkerDots) {
                    // ── 标记点（买点/卖点等）────────────────
                    markers.forEach { marker ->
                        if (marker == mergedEntryMarker) return@forEach
                        val markerGlobalIndex = candles.findMarkerIndex(marker.date)
                        if (markerGlobalIndex in startIndex..endIndex) {
                            val visibleIndex = markerGlobalIndex - startIndex
                            val x = chartLeft + visibleIndex.toFloat() * candleSpacing + candleSpacing / 2f
                            val candle = candles[markerGlobalIndex]
                            val candleHighY = chartHeightPx - (candle.high - priceRange.min) / priceRange.range * chartHeightPx
                            val candleLowY = chartHeightPx - (candle.low - priceRange.min) / priceRange.range * chartHeightPx
                            val markerPriceY = marker.price?.let { price ->
                                chartHeightPx - (price - priceRange.min) / priceRange.range * chartHeightPx
                            }
                            val y = markerPriceY ?: if (marker.position == MarkerPosition.ABOVE) {
                                candleHighY
                            } else {
                                candleLowY
                            }

                            drawCircle(
                                color = marker.color,
                                radius = 5f,
                                center = Offset(x, y)
                            )
                        }
                    }
                }

                // ── 主图完整十字星（最后绘制，始终在顶层）────────────────
                val hoverVisibleIndex = crosshairState.hoverIndex - startIndex
                val hoverCandle = if (hoverVisibleIndex in visibleCandlesList.indices)
                    visibleCandlesList[hoverVisibleIndex] else null

                if (hoverCandle != null) {
                    drawCrosshairFull(
                        visibleIndex = hoverVisibleIndex,
                        spacing = candleSpacing,
                        hoverY = crosshairState.hoverY,
                        hoverDate = hoverCandle.date,
                        chartLeft = chartLeft,
                        chartWidth = chartWidth,
                        chartHeightPx = chartHeightPx,
                        priceMin = priceRange.min,
                        priceRangeVal = priceRange.range,
                        crosshairColor = crosshairColor,
                        labelBgColor = labelBgColor,
                        labelTextColor = labelTextColor,
                        textMeasurer = textMeasurer
                    )
                }
            }
        }

        // 成交量图
        if (config.showVolume) {
            Spacer(modifier = Modifier.height(4.dp))
            VolumeChart(
                candles = visibleCandlesList,
                upColor = upColor,
                downColor = downColor,
                gridColor = gridColor,
                height = config.volumeChartHeight,
                crosshairState = crosshairState,
                startIndex = startIndex,
                crosshairColor = crosshairColor,
                borderColor = gridColor.copy(alpha = 0.5f),
                priceAxisWidthPx = priceAxisWidthPx
            )
        }

        // MACD图
        if (config.showMacd) {
            Spacer(modifier = Modifier.height(4.dp))
            MacdChart(
                data = data,
                startIndex = startIndex,
                endIndex = endIndex,
                positiveColor = macdPositiveColor,
                negativeColor = macdNegativeColor,
                gridColor = gridColor,
                textColor = textColor,
                height = config.indicatorChartHeight,
                crosshairState = crosshairState,
                crosshairColor = crosshairColor,
                borderColor = gridColor.copy(alpha = 0.5f),
                priceAxisWidthPx = priceAxisWidthPx
            )
        }

        // RSI图
        if (config.showRsi) {
            Spacer(modifier = Modifier.height(4.dp))
            RsiChart(
                data = data,
                startIndex = startIndex,
                endIndex = endIndex,
                rsiColor = rsiColor,
                gridColor = gridColor,
                textColor = textColor,
                height = config.indicatorChartHeight,
                crosshairState = crosshairState,
                crosshairColor = crosshairColor,
                borderColor = gridColor.copy(alpha = 0.5f),
                priceAxisWidthPx = priceAxisWidthPx
            )
        }

        // 图例
        if (config.showLegend) {
            ChartLegend(config = config, upColor = upColor, downColor = downColor,
                emaColor = emaColor, maColor = maColor, rsiColor = rsiColor)
        }
    }
}

private fun List<CandleData>.findMarkerIndex(markerDate: String): Int {
    val exact = indexOfFirst { it.date.matchesCandleDateToken(markerDate, requireMinute = true) }
    if (exact >= 0) return exact

    return indexOfFirst { it.date.matchesCandleDateToken(markerDate, requireMinute = false) }
}

private fun Float.isSamePrice(other: Float): Boolean =
    abs(this - other) < 0.005f

private fun String.matchesCandleDateToken(token: String, requireMinute: Boolean): Boolean {
    if (this == token || startsWith(token) || token.startsWith(this)) return true

    val candleMinute = take(16)
    val tokenMinute = token.take(16)
    if (candleMinute == tokenMinute || candleMinute.endsWith(tokenMinute)) return true

    if (length >= 16 && token.length >= 11) {
        val candleMonthDayMinute = substring(5, 16)
        val tokenMonthDayMinute = token.take(11)
        if (candleMonthDayMinute == tokenMonthDayMinute) return true
    }

    if (requireMinute) return false

    val tokenDate = token.take(10)
    if (tokenDate.length == 10 && tokenDate.count { it == '-' } == 2) {
        if (take(10) == tokenDate || take(10).endsWith(tokenDate)) return true
    }

    val tokenMonthDay = token.take(5)
    return tokenMonthDay.length == 5 && tokenMonthDay[2] == '-' &&
        length >= 10 && substring(5, 10) == tokenMonthDay
}

// ==================== 空图表占位 ====================

@Composable
private fun EmptyChartPanel(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(400.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center
    ) {
        Text("暂无数据", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ==================== 副图组件 ====================

/**
 * 成交量图
 *
 * 通过 [CrosshairState] 接入十字星，实时显示当前悬停处的成交量数值。
 */
@Composable
private fun VolumeChart(
    candles: List<CandleData>,
    upColor: Color,
    downColor: Color,
    gridColor: Color,
    height: Int,
    crosshairState: CrosshairState? = null,
    startIndex: Int = 0,
    crosshairColor: Color = Color.Gray.copy(alpha = 0.4f),
    borderColor: Color = Color.Gray.copy(alpha = 0.2f),
    priceAxisWidthPx: Float
) {
    val maxVolume = candles.maxOfOrNull { it.volume } ?: 1f
    val textMeasurer = rememberTextMeasurer()

    // 在 composition phase 读取 hoverIndex，确保 Compose 能追踪状态变化并触发重组
    val hoverVisibleIndex = (crosshairState?.hoverIndex ?: -1) - startIndex
    val hoverCandle = candles.getOrNull(hoverVisibleIndex)

    Box(modifier = Modifier.fillMaxWidth().height(height.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val chartLeft = priceAxisWidthPx
            val chartWidth = (size.width - priceAxisWidthPx * 2f).coerceAtLeast(1f)
            val chartHeightPx = size.height - 20f
            val spacing = chartWidth / candles.size
            val barWidth = spacing * 0.7f

            // 淡边界框
            drawRect(color = borderColor, topLeft = Offset(chartLeft, 0f),
                size = Size(chartWidth, chartHeightPx), style = Stroke(width = 0.8f))

            drawLine(gridColor, Offset(chartLeft, chartHeightPx), Offset(chartLeft + chartWidth, chartHeightPx), strokeWidth = 1f)

            candles.forEachIndexed { index, candle ->
                val x = chartLeft + index.toFloat() * spacing + spacing / 2f
                val volHeight = candle.volume / maxVolume * chartHeightPx * 0.9f
                val isUp = candle.close >= candle.open
                drawRect(
                    color = if (isUp) upColor else downColor,
                    topLeft = Offset(x - barWidth / 2, chartHeightPx - volHeight),
                    size = Size(barWidth, volHeight),
                    alpha = 0.7f
                )
            }

            // 十字星竖线 + 实时数値
            if (hoverVisibleIndex in candles.indices && hoverCandle != null) {
                drawCrosshairVertical(hoverVisibleIndex, spacing, chartHeightPx, crosshairColor, chartLeft)

                // 右上角成交量数值（加背景防遮挡）
                val vol = hoverCandle.volume
                val volText = when {
                    vol >= 1_0000_0000f -> "VOL: ${(vol / 1_0000_0000f * 100).toLong() / 100.0}亿"
                    vol >= 1_0000f      -> "VOL: ${(vol / 1_0000f * 100).toLong() / 100.0}万"
                    else               -> "VOL: ${vol.toLong()}"
                }
                val volTm = textMeasurer.measure(volText,
                    style = TextStyle(color = crosshairColor, fontSize = chartSp(9), fontWeight = FontWeight.SemiBold))
                val vPadH = 3f; val vPadV = 2f
                val vLabelW = volTm.size.width + vPadH * 2
                val vLabelH = volTm.size.height + vPadV * 2
                val vLabelX = chartLeft + chartWidth - vLabelW - 4f
                val vLabelY = 4f
                drawRect(
                    color = crosshairColor.copy(alpha = 0.12f),
                    topLeft = Offset(vLabelX, vLabelY),
                    size = Size(vLabelW, vLabelH)
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = volText,
                    style = TextStyle(color = crosshairColor, fontSize = chartSp(9), fontWeight = FontWeight.SemiBold),
                    topLeft = Offset(vLabelX + vPadH, vLabelY + vPadV)
                )
            }
        }
    }
}

/**
 * MACD图
 *
 * 通过 [CrosshairState] 接入十字星，实时显示 DIF / DEA / MACD 当前数値。
 */
@Composable
private fun MacdChart(
    data: CandleChartData,
    startIndex: Int,
    endIndex: Int,
    positiveColor: Color,
    negativeColor: Color,
    gridColor: Color,
    textColor: Color,
    height: Int,
    crosshairState: CrosshairState? = null,
    crosshairColor: Color = Color.Gray.copy(alpha = 0.4f),
    borderColor: Color = Color.Gray.copy(alpha = 0.2f),
    priceAxisWidthPx: Float
) {
    val difValues = data.macdDif.subList(startIndex, endIndex + 1)
    val deaValues = data.macdDea.subList(startIndex, endIndex + 1)
    val barValues = data.macdBar.subList(startIndex, endIndex + 1)

    val allValues = difValues.filterNotNull() + deaValues.filterNotNull() + barValues.filterNotNull()
    if (allValues.isEmpty()) return

    val maxValue = allValues.maxOrNull() ?: 0f
    val minValue = allValues.minOrNull() ?: 0f
    val range = maxValue - minValue

    // 在 composition phase 读取 hoverIndex，确保 Compose 能追踪并触发重组
    val hoverVisibleIndex = (crosshairState?.hoverIndex ?: -1) - startIndex
    val hoverDif = difValues.getOrNull(hoverVisibleIndex)
    val hoverDea = deaValues.getOrNull(hoverVisibleIndex)
    val hoverBar = barValues.getOrNull(hoverVisibleIndex)

    Box(modifier = Modifier.fillMaxWidth().height(height.dp)) {
        val textMeasurer = rememberTextMeasurer()

        Canvas(modifier = Modifier.fillMaxSize()) {
            val chartLeft = priceAxisWidthPx
            val chartWidth = (size.width - priceAxisWidthPx * 2f).coerceAtLeast(1f)
            val chartHeightPx = size.height - 25f
            val spacing = chartWidth / difValues.size

            // 零轴 Y（与线坐标系完全一致）
            val zeroY = chartHeightPx - (0f - minValue) / range * chartHeightPx

            // 淡边界框
            drawRect(color = borderColor, topLeft = Offset(chartLeft, 0f),
                size = Size(chartWidth, chartHeightPx), style = Stroke(width = 0.8f))

            // 零轴
            drawLine(gridColor.copy(alpha = 0.5f), Offset(chartLeft, zeroY), Offset(chartLeft + chartWidth, zeroY), strokeWidth = 1f)

            // 柱状图（与线共用坐标系）
            barValues.forEachIndexed { index, value ->
                if (value != null) {
                    val x = chartLeft + index.toFloat() * spacing + spacing / 2f
                    val valueY = chartHeightPx - (value - minValue) / range * chartHeightPx
                    val barTop = minOf(valueY, zeroY)
                    val barBottom = maxOf(valueY, zeroY)
                    val barHeight = maxOf(barBottom - barTop, 1f)
                    val color = if (value >= 0) positiveColor else negativeColor
                    drawRect(color = color, topLeft = Offset(x - spacing * 0.3f, barTop), size = Size(spacing * 0.6f, barHeight))
                }
            }

            // DIF线
            drawIndicatorPath(difValues, chartLeft, chartWidth, chartHeightPx, minValue, range, positiveColor, 1.2f)

            // DEA线
            drawIndicatorPath(deaValues, chartLeft, chartWidth, chartHeightPx, minValue, range, negativeColor, 1.2f)

            // 标签（静态显示在右侧）
            val macdTitle = "MACD"
            val macdTitleLayout = textMeasurer.measure(
                macdTitle,
                style = TextStyle(color = textColor, fontSize = chartSp(9))
            )
            drawText(
                textMeasurer = textMeasurer,
                text = macdTitle,
                style = TextStyle(color = textColor, fontSize = chartSp(9)),
                topLeft = Offset(size.width - macdTitleLayout.size.width, 0f)
            )

            // 十字星竖线 + 实时数值
            if (hoverVisibleIndex in difValues.indices) {
                drawCrosshairVertical(hoverVisibleIndex, spacing, chartHeightPx, crosshairColor, chartLeft)

                // 右上角显示 DIF / DEA / MACD 当前数值（加背景防遮挡）
                fun Float?.fmt(): String {
                    if (this == null) return "--"
                    val sign = if (this >= 0) "+" else ""
                    val scaled = (this * 10000).toLong() / 10000.0
                    return "$sign$scaled"
                }
                val labelText = "DIF: ${hoverDif.fmt()}  DEA: ${hoverDea.fmt()}  BAR: ${hoverBar.fmt()}"
                val macdTm = textMeasurer.measure(labelText,
                    style = TextStyle(color = crosshairColor, fontSize = chartSp(9), fontWeight = FontWeight.SemiBold))
                val mPadH = 3f; val mPadV = 2f
                val mLabelW = macdTm.size.width + mPadH * 2
                val mLabelH = macdTm.size.height + mPadV * 2
                val mLabelX = chartLeft + chartWidth - mLabelW - 4f
                val mLabelY = 4f
                drawRect(
                    color = crosshairColor.copy(alpha = 0.12f),
                    topLeft = Offset(mLabelX, mLabelY),
                    size = Size(mLabelW, mLabelH)
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = labelText,
                    style = TextStyle(color = crosshairColor, fontSize = chartSp(9), fontWeight = FontWeight.SemiBold),
                    topLeft = Offset(mLabelX + mPadH, mLabelY + mPadV)
                )
            }
        }
    }
}

/**
 * RSI图
 *
 * 通过 [CrosshairState] 接入十字星，实时显示 RSI 当前数値。
 */
@Composable
private fun RsiChart(
    data: CandleChartData,
    startIndex: Int,
    endIndex: Int,
    rsiColor: Color,
    gridColor: Color,
    textColor: Color,
    height: Int,
    crosshairState: CrosshairState? = null,
    crosshairColor: Color = Color.Gray.copy(alpha = 0.4f),
    borderColor: Color = Color.Gray.copy(alpha = 0.2f),
    priceAxisWidthPx: Float
) {
    val rsiValues = data.rsi6.subList(startIndex, endIndex + 1)

    // 在 composition phase 读取 hoverIndex，确保 Compose 能追踪并触发重组
    val hoverVisibleIndex = (crosshairState?.hoverIndex ?: -1) - startIndex
    val hoverRsi = rsiValues.getOrNull(hoverVisibleIndex)

    Box(modifier = Modifier.fillMaxWidth().height(height.dp)) {
        val textMeasurer = rememberTextMeasurer()

        Canvas(modifier = Modifier.fillMaxSize()) {
            val chartLeft = priceAxisWidthPx
            val chartWidth = (size.width - priceAxisWidthPx * 2f).coerceAtLeast(1f)
            val chartHeightPx = size.height - 25f
            val spacing = chartWidth / rsiValues.size

            // 淡边界框
            drawRect(color = borderColor, topLeft = Offset(chartLeft, 0f),
                size = Size(chartWidth, chartHeightPx), style = Stroke(width = 0.8f))

            // 参考线 (30, 50, 70)
            listOf(30f, 50f, 70f).forEach { level ->
                val y = chartHeightPx - (level / 100f) * chartHeightPx
                drawLine(
                    if (level == 50f) gridColor else gridColor.copy(alpha = 0.3f),
                    Offset(chartLeft, y), Offset(chartLeft + chartWidth, y), strokeWidth = 1f,
                    pathEffect = if (level != 50f) PathEffect.dashPathEffect(floatArrayOf(4f, 4f)) else null
                )
            }

            // RSI线
            val path = Path()
            var first = true
            rsiValues.forEachIndexed { index, value ->
                if (value != null) {
                    val x = chartLeft + index.toFloat() * spacing + spacing / 2f
                    val y = chartHeightPx - (value / 100f) * chartHeightPx
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                }
            }
            drawPath(path, rsiColor, style = Stroke(width = 1.2f))

            // 标签（静态显示在右侧）
            val rsiTitle = "RSI"
            val rsiTitleLayout = textMeasurer.measure(
                rsiTitle,
                style = TextStyle(color = rsiColor, fontSize = chartSp(9))
            )
            drawText(
                textMeasurer = textMeasurer,
                text = rsiTitle,
                style = TextStyle(color = rsiColor, fontSize = chartSp(9)),
                topLeft = Offset(size.width - rsiTitleLayout.size.width, 0f)
            )

            // 十字星竖线 + 实时数値
            if (hoverVisibleIndex in rsiValues.indices) {
                drawCrosshairVertical(hoverVisibleIndex, spacing, chartHeightPx, crosshairColor, chartLeft)

                // 右上角显示 RSI 当前数值（加背景防遮挡）
                val rsiText = if (hoverRsi != null) {
                    val rsiRounded = (hoverRsi * 100).toLong() / 100.0
                    "RSI: $rsiRounded"
                } else "RSI: --"
                val rsiTm = textMeasurer.measure(rsiText,
                    style = TextStyle(color = rsiColor, fontSize = chartSp(9), fontWeight = FontWeight.SemiBold))
                val rPadH = 3f; val rPadV = 2f
                val rLabelW = rsiTm.size.width + rPadH * 2
                val rLabelH = rsiTm.size.height + rPadV * 2
                val rLabelX = chartLeft + chartWidth - rLabelW - 4f
                val rLabelY = 4f
                drawRect(
                    color = rsiColor.copy(alpha = 0.12f),
                    topLeft = Offset(rLabelX, rLabelY),
                    size = Size(rLabelW, rLabelH)
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = rsiText,
                    style = TextStyle(color = rsiColor, fontSize = chartSp(9), fontWeight = FontWeight.SemiBold),
                    topLeft = Offset(rLabelX + rPadH, rLabelY + rPadV)
                )
            }
        }
    }
}

// ==================== 图例 ====================

@Composable
private fun ChartLegend(
    config: CandleChartConfig,
    upColor: Color,
    downColor: Color,
    emaColor: Color,
    maColor: Color,
    rsiColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(color = upColor, text = "涨")
        Spacer(modifier = Modifier.width(12.dp))
        LegendItem(color = downColor, text = "跌")

        if (config.showEma) {
            Spacer(modifier = Modifier.width(12.dp))
            LegendItem(color = emaColor, text = "EMA20", isLine = true)
        }
        if (config.showMa) {
            Spacer(modifier = Modifier.width(12.dp))
            LegendItem(color = maColor, text = "MA20", isLine = true)
        }
        if (config.showRsi) {
            Spacer(modifier = Modifier.width(12.dp))
            LegendItem(color = rsiColor, text = "RSI", isLine = true)
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String, isLine: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isLine) {
            Box(modifier = Modifier.width(16.dp).height(2.dp).background(color))
        } else {
            Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(1.dp)))
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ==================== 绘制辅助函数 ====================

/**
 * 价格范围数据类
 */
private data class PriceRange(val min: Float, val max: Float) {
    val range: Float get() = max - min
}

private fun calculatePriceAxisWidth(
    priceRange: PriceRange,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle
): Float {
    val widestLabelWidth = (0..4).maxOf { i ->
        val price = priceRange.min + priceRange.range * i / 4
        textMeasurer.measure(price.formatPrice(), style = textStyle).size.width
    }.toFloat()
    return (widestLabelWidth + PRICE_AXIS_LABEL_GAP_PX).coerceAtLeast(PRICE_AXIS_MIN_WIDTH_PX)
}

/**
 * 计算价格范围
 */
private fun calculatePriceRange(
    candles: List<CandleData>,
    data: CandleChartData,
    config: CandleChartConfig,
    tradePlan: CandleTradePlan?,
    markers: List<CandleMarker>
): PriceRange {
    val prices = candles.flatMap { listOf(it.high, it.low) }.toMutableList()

    if (config.showEma) {
        val s = data.candles.indexOf(candles.first())
        val e = data.candles.indexOf(candles.last())
        prices.addAll(data.ema20.subList(s, e + 1).filterNotNull())
    }
    if (config.showMa) {
        val s = data.candles.indexOf(candles.first())
        val e = data.candles.indexOf(candles.last())
        data.ma20?.subList(s, e + 1)?.filterNotNull()?.let { prices.addAll(it) }
    }
    tradePlan?.let {
        prices.add(it.entryPrice)
        prices.add(it.stopLossPrice)
        prices.add(it.targetPrice)
    }
    prices.addAll(markers.mapNotNull { it.price })

    val min = prices.minOrNull() ?: 0f
    val max = prices.maxOrNull() ?: 1f
    val padding = (max - min) * 0.05f
    return PriceRange(min - padding, max + padding)
}

/**
 * 绘制网格线
 */
private fun DrawScope.drawGridLines(
    chartLeft: Float,
    chartWidth: Float,
    chartHeightPx: Float,
    gridColor: Color
) {
    for (i in 0..4) {
        val y = chartHeightPx * i / 4
        drawLine(gridColor, Offset(chartLeft, y), Offset(chartLeft + chartWidth, y), strokeWidth = 1f)
    }
    for (i in 0..5) {
        val x = chartLeft + chartWidth * i / 5
        drawLine(gridColor, Offset(x, 0f), Offset(x, chartHeightPx), strokeWidth = 1f)
    }
}

/**
 * 绘制指标线（按价格坐标系）
 */
private fun DrawScope.drawIndicatorLine(
    candles: List<CandleData>,
    indicatorValues: List<Float?>,
    priceRange: PriceRange,
    chartLeft: Float,
    chartWidth: Float,
    chartHeightPx: Float,
    color: Color,
    lineWidth: Float
) {
    val path = Path()
    var first = true
    val spacing = chartWidth / candles.size

    indicatorValues.forEachIndexed { index, value ->
        if (value != null) {
            val x = chartLeft + index.toFloat() * spacing + spacing / 2f
            val y = chartHeightPx - (value - priceRange.min) / priceRange.range * chartHeightPx
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
    }
    drawPath(path, color, style = Stroke(width = lineWidth))
}

/**
 * 绘制指标路径（按自定义 minValue/range 坐标系）
 */
private fun DrawScope.drawIndicatorPath(
    values: List<Float?>,
    chartLeft: Float,
    chartWidth: Float,
    chartHeightPx: Float,
    minValue: Float,
    range: Float,
    color: Color,
    lineWidth: Float
) {
    val path = Path()
    var first = true
    val spacing = chartWidth / values.size

    values.forEachIndexed { index, value ->
        if (value != null) {
            val x = chartLeft + index.toFloat() * spacing + spacing / 2f
            val y = chartHeightPx - (value - minValue) / range * chartHeightPx
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
    }
    drawPath(path, color, style = Stroke(width = lineWidth))
}

/**
 * 绘制布林带
 */
private fun DrawScope.drawBollingerBands(
    candles: List<CandleData>,
    data: CandleChartData,
    startIndex: Int,
    endIndex: Int,
    priceRange: PriceRange,
    chartLeft: Float,
    chartWidth: Float,
    chartHeightPx: Float,
    upperColor: Color,
    midColor: Color,
    lowerColor: Color
) {
    val spacing = chartWidth / candles.size

    fun drawBand(values: List<Float?>?, color: Color, width: Float) {
        values ?: return
        val path = Path()
        var first = true
        values.forEachIndexed { index, value ->
            if (value != null) {
                val x = chartLeft + index.toFloat() * spacing + spacing / 2f
                val y = chartHeightPx - (value - priceRange.min) / priceRange.range * chartHeightPx
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
        }
        drawPath(path, color, style = Stroke(width = width))
    }

    drawBand(data.bollUpper?.subList(startIndex, endIndex + 1), upperColor, 1f)
    drawBand(data.ma20?.subList(startIndex, endIndex + 1), midColor, 1.5f)
    drawBand(data.bollLower?.subList(startIndex, endIndex + 1), lowerColor, 1f)
}

private fun DrawScope.drawTradePlan(
    plan: CandleTradePlan,
    priceRange: PriceRange,
    chartLeft: Float,
    chartWidth: Float,
    chartHeightPx: Float,
    profitColor: Color,
    lossColor: Color
) {
    fun yOf(price: Float): Float =
        chartHeightPx - (price - priceRange.min) / priceRange.range * chartHeightPx

    val entryY = yOf(plan.entryPrice)
    val stopY = yOf(plan.stopLossPrice)
    val targetY = yOf(plan.targetPrice)
    val profitTop = minOf(entryY, targetY)
    val profitBottom = maxOf(entryY, targetY)
    val lossTop = minOf(entryY, stopY)
    val lossBottom = maxOf(entryY, stopY)

    drawRect(
        color = profitColor.copy(alpha = 0.10f),
        topLeft = Offset(chartLeft, profitTop),
        size = Size(chartWidth, (profitBottom - profitTop).coerceAtLeast(1f))
    )
    drawRect(
        color = lossColor.copy(alpha = 0.10f),
        topLeft = Offset(chartLeft, lossTop),
        size = Size(chartWidth, (lossBottom - lossTop).coerceAtLeast(1f))
    )

    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
    drawPricePlanLine(chartLeft, chartWidth, entryY, profitColor, null)
    drawPricePlanLine(chartLeft, chartWidth, targetY, profitColor, dash)
    drawPricePlanLine(chartLeft, chartWidth, stopY, lossColor, dash)
}

private fun DrawScope.drawPricePlanLine(
    chartLeft: Float,
    chartWidth: Float,
    y: Float,
    color: Color,
    pathEffect: PathEffect?
) {
    drawLine(
        color = color.copy(alpha = 0.82f),
        start = Offset(chartLeft, y),
        end = Offset(chartLeft + chartWidth, y),
        strokeWidth = 1.3f,
        pathEffect = pathEffect
    )
}

private fun DrawScope.drawAnnotationLabels(
    labels: List<ChartAnnotationLabel>,
    chartLeft: Float,
    chartHeightPx: Float,
    backgroundColor: Color,
    textMeasurer: TextMeasurer
) {
    if (labels.isEmpty()) return

    val padH = 5f
    val padV = 3f
    val gap = 4f
    val measuredLabels = labels
        .sortedBy { it.anchorY }
        .map { label ->
            val style = TextStyle(
                color = label.color,
                fontSize = chartSp(9),
                fontWeight = FontWeight.SemiBold
            )
            val layout = textMeasurer.measure(label.text, style = style)
            Triple(label, style, layout)
        }

    val labelTops = mutableListOf<Float>()
    var cursorY = 4f
    measuredLabels.forEach { (label, _, layout) ->
        val labelH = layout.size.height + padV * 2
        val desiredY = (label.anchorY - labelH / 2f).coerceAtLeast(4f)
        val labelY = max(desiredY, cursorY)
        labelTops.add(labelY)
        cursorY = labelY + labelH + gap
    }
    val overflow = (cursorY - gap) - (chartHeightPx - 4f)
    if (overflow > 0f) {
        for (index in labelTops.indices) {
            labelTops[index] = (labelTops[index] - overflow).coerceAtLeast(4f)
        }
    }

    measuredLabels.forEachIndexed { index, (label, style, layout) ->
        val labelW = layout.size.width + padH * 2
        val labelH = layout.size.height + padV * 2
        val y = labelTops[index]
        if (y + labelH <= chartHeightPx - 4f) {
            val labelX = chartLeft + 6f
            drawRect(
                color = backgroundColor,
                topLeft = Offset(labelX, y),
                size = Size(labelW, labelH)
            )
            drawRect(
                color = label.color,
                topLeft = Offset(labelX, y),
                size = Size(3f, labelH)
            )
            drawText(
                textMeasurer = textMeasurer,
                text = label.text,
                style = style,
                topLeft = Offset(labelX + padH, y + padV)
            )
        }
    }
}

// ==================== 数据转换函数 ====================

/**
 * 将CandleChartData转换为StockChartData（向后兼容）
 */
fun CandleChartData.toStockChartData(): StockChartData {
    return StockChartData(
        code = this.code,
        name = this.name,
        candles = this.candles,
        ema20 = this.ema20,
        rsi = this.rsi6,
        volume = this.volumes,
        tradeDate = this.candles.lastOrNull()?.date ?: ""
    )
}

/**
 * 将StockKLineInfo转换为CandleChartData
 */
fun StockKLineInfo.toCandleChartData(): CandleChartData {
    return CandleChartData(
        code = this.stockInfo.code,
        name = this.stockInfo.name,
        candles = this.kLines.map { kline ->
            CandleData(
                date = kline.date.toString(),
                open = kline.open,
                high = kline.high,
                low = kline.low,
                close = kline.close,
                volume = kline.volume,
                turnover = kline.turnover,
                changePercent = kline.changePercent
            )
        },
        volumes = this.kLines.map { it.volume },
        ema20 = this.kLines.map { it.ema20 },
        rsi6 = this.kLines.map { it.rsi6 },
        macdDif = this.kLines.map { it.macdDif },
        macdDea = this.kLines.map { it.macdDea },
        macdBar = this.kLines.map { it.macdBar },
        ema5 = this.kLines.map { it.ema5 },
        ema10 = this.kLines.map { it.ema10 },
        ema60 = this.kLines.map { it.ema60 },
        rsi12 = this.kLines.map { it.rsi12 },
        rsi24 = this.kLines.map { it.rsi24 },
        ma5 = this.kLines.map { it.ma5 },
        ma10 = this.kLines.map { it.ma10 },
        ma20 = this.kLines.map { it.ma20 },
        ma60 = this.kLines.map { it.ma60 },
        bollUpper = calculateBollingerBands(this.kLines.map { it.close.toDouble() }, 20, 2.0).first,
        bollMid = calculateBollingerBands(this.kLines.map { it.close.toDouble() }, 20, 2.0).second,
        bollLower = calculateBollingerBands(this.kLines.map { it.close.toDouble() }, 20, 2.0).third
    )
}

/**
 * 将Candle列表转换为CandleChartData
 */
fun List<model.Candle>.toChartData(tsCode: String, name: String): CandleChartData {
    return this.toCandleChartData(tsCode, name)
}

/**
 * 计算布林带
 */
private fun calculateBollingerBands(
    data: List<Double>,
    period: Int = 20,
    multiplier: Double = 2.0
): Triple<List<Float?>, List<Float?>, List<Float?>> {
    val upper = mutableListOf<Float?>()
    val mid = mutableListOf<Float?>()
    val lower = mutableListOf<Float?>()

    for (i in data.indices) {
        if (i < period - 1) {
            upper.add(null); mid.add(null); lower.add(null)
        } else {
            val slice = data.subList(i - period + 1, i + 1)
            val sma = slice.average()
            val variance = slice.map { (it - sma).pow(2) }.average()
            val stdDev = kotlin.math.sqrt(variance)
            upper.add((sma + multiplier * stdDev).toFloat())
            mid.add(sma.toFloat())
            lower.add((sma - multiplier * stdDev).toFloat())
        }
    }
    return Triple(upper, mid, lower)
}
