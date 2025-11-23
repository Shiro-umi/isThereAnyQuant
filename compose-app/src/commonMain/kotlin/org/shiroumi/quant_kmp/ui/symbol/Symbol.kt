package org.shiroumi.quant_kmp.ui.symbol

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import model.Candle
import model.symbol.Wave
import model.symbol.WaveType
import org.shiroumi.quant_kmp.createHttpClient
import org.shiroumi.quant_kmp.json
import org.shiroumi.quant_kmp.ui.theme.Refresh
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.json.put


@Composable
fun Symbol() {
    var list: List<Candle> by remember { mutableStateOf(mutableListOf()) }
    var selectedCandle by remember { mutableStateOf<Candle?>(null) }
    var lastSelectedCandle by remember { mutableStateOf<Candle?>(null) }
    var popupOffset by remember { mutableStateOf(Offset.Zero) }
    var symbolList by remember { mutableStateOf<List<Wave>>(mutableListOf()) }
    var isLoading by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // Track the last selected candle for exit animation
    LaunchedEffect(selectedCandle) {
        if (selectedCandle != null) {
            lastSelectedCandle = selectedCandle
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth
        val density = LocalDensity.current

        key(list, symbolList) {
            SymbolCanvas(
                modifier = Modifier.fillMaxSize(),
                data = list,
                symbols = symbolList
            ) { candle, offset ->
                selectedCandle = candle
                popupOffset = offset
            }
        }

        // Top right buttons
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { refreshTrigger++ }
            ) {
                Icon(
                    imageVector = Refresh,
                    contentDescription = "Refresh"
                )
            }

            OutlinedButton(
                onClick = {
                    if (symbolList.isNotEmpty()) {
                        isLoading = true
                    }
                }
            ) {
                Text("Submit")
            }
        }

        // Fullscreen loading
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { /* Block interactions */ },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.5f)
                ) {}
                CircularProgressIndicator()
            }
        }

        val currentSymbol = (selectedCandle ?: lastSelectedCandle)?.let { candle ->
            symbolList.find { it.candle.date == candle.date }
        }

        var popupWidth by remember { mutableIntStateOf(0) }

        // 调整popup位置，确保不超出屏幕
        val adjustedOffset = remember(popupOffset, popupWidth, screenWidth) {
            val x = popupOffset.x.roundToInt()
            val y = popupOffset.y.roundToInt()

            // 如果popup会超出右边界，则向左偏移
            val adjustedX = if (x + popupWidth > screenWidth) {
                (screenWidth - popupWidth).coerceAtLeast(0)
            } else {
                x
            }

            IntOffset(adjustedX, y)
        }

        Box(
            modifier = Modifier.offset { adjustedOffset }
        ) {
            AnimatedVisibility(
                visible = selectedCandle != null,
                enter = fadeIn(animationSpec = tween(200)) + scaleIn(
                    initialScale = 0.9f,
                    animationSpec = tween(200)
                ),
                exit = fadeOut(animationSpec = tween(150)) + scaleOut(
                    targetScale = 0.9f,
                    animationSpec = tween(150)
                )
            ) {
                // Use lastSelectedCandle to maintain content during exit animation
                val candle = lastSelectedCandle ?: return@AnimatedVisibility
                SymbolPopupWindow(
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        popupWidth = coordinates.size.width
                    },
                    candle = candle,
                    currentWaveType = currentSymbol?.type,
                    onTypeSelected = { type ->
                        val newWave = Wave(candle, type)
                        symbolList = symbolList.filter { it.candle.date != candle.date } + newWave
                    },
                    onTypeUnselected = { type ->
                        symbolList = symbolList.filter {
                            !(it.candle.date == candle.date && it.type == type)
                        }
                    },
                    onDismiss = { selectedCandle = null },
                    isVisible = selectedCandle != null
                )
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        val client = createHttpClient()
        try {
            val res = client.get("/symbol")
            list = json.decodeFromString<List<Candle>>(res.bodyAsText())
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            client.close()
            isLoading = false
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading || symbolList.isEmpty()) return@LaunchedEffect
        val client = createHttpClient()
        try {
            client.post("/symbol/submit") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("candles",json.encodeToString(list) )
                    put("symbols", json.encodeToString(symbolList))
                })
            }
            // Clear cache and reset canvas status
            symbolList = mutableListOf()
            selectedCandle = null
            lastSelectedCandle = null
            // 提交成功后自动刷新数据
            refreshTrigger++
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            client.close()
            isLoading = false
        }
    }
}

@Composable
fun SymbolPopupWindow(
    modifier: Modifier = Modifier,
    candle: Candle,
    currentWaveType: WaveType?,
    onTypeSelected: (WaveType) -> Unit,
    onTypeUnselected: (WaveType) -> Unit,
    onDismiss: () -> Unit,
    isVisible: Boolean = false
) {
    val elevation by animateDpAsState(
        targetValue = if (isVisible) 8.dp else 0.dp,
        animationSpec = tween(200)
    )

    Card(
        modifier = modifier.width(200.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Type",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures {
                        val checked = currentWaveType !is WaveType.High
                        if (checked) {
                            onTypeSelected(WaveType.High)
                        } else {
                            onTypeUnselected(WaveType.High)
                        }
                        onDismiss()
                    }
                }
            ) {
                Checkbox(
                    checked = currentWaveType is WaveType.High,
                    onCheckedChange = { checked ->
                        if (checked) {
                            onTypeSelected(WaveType.High)
                        } else {
                            onTypeUnselected(WaveType.High)
                        }
                        onDismiss()
                    }
                )
                Text("High")
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures {
                        val checked = currentWaveType !is WaveType.Low
                        if (checked) {
                            onTypeSelected(WaveType.Low)
                        } else {
                            onTypeUnselected(WaveType.Low)
                        }
                        onDismiss()
                    }
                }
            ) {
                Checkbox(
                    checked = currentWaveType is WaveType.Low,
                    onCheckedChange = { checked ->
                        if (checked) {
                            onTypeSelected(WaveType.Low)
                        } else {
                            onTypeUnselected(WaveType.Low)
                        }
                        onDismiss()
                    }
                )
                Text("Low")
            }
        }
    }
}


@Composable
fun SymbolCanvas(
    modifier: Modifier = Modifier,
    data: List<Candle> = listOf(),
    symbols: List<Wave> = emptyList(),
    onCandleSelected: ((Candle, Offset) -> Unit)? = null
) {
    if (data.isEmpty()) return

    // 状态管理
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var selectedIndex by remember { mutableIntStateOf(-1) }

    // 创建符号类型到背景颜色的映射（使用半透明颜色作为背景）
    val symbolBackgroundColors = remember {
        mapOf(
            WaveType.High::class to Color(0xFFFF6B6B).copy(alpha = 0.3f),  // 半透明红色背景表示高点
            WaveType.Low::class to Color(0xFF4ECDC4).copy(alpha = 0.3f)     // 半透明青色背景表示低点
        )
    }

    // 创建日期到符号的映射以便快速查找（使用日期而不是对象引用）
    val dateSymbolMap = remember(symbols) {
        symbols.associateBy { it.candle.date }
    }

    // 颜色
    val upColor = Color(0xFF26A69A)
    val downColor = Color(0xFFEF5350)
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val selectedColor = Color(0xFF2196F3).copy(alpha = 0.3f)

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            // 处理鼠标滚轮和触控板缩放
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll) {
                        val scrollDelta = event.changes.first().scrollDelta.y
                        val oldScale = scale
                        scale = (scale * (1f - scrollDelta * 0.05f)).coerceIn(0.5f, 10f)

                        // 计算缩放中心点，调整偏移以保持缩放中心不变
                        val centerX = size.width / 2f
                        offsetX = centerX - (centerX - offsetX) * (scale / oldScale)
                    }
                }
            }
        }
            .pointerInput(Unit) {
                // 处理拖动
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x

                    // 限制拖动范围
                    val maxOffset = 0f
                    val minOffset = -(data.size * 20f * scale - size.width)
                    offsetX = offsetX.coerceIn(min(minOffset, maxOffset), maxOffset)
                }
            }
            .pointerInput(Unit) {
                // 处理点击选中
                detectTapGestures { offset ->
                    val padding = 50f
                    val chartWidth = size.width - padding * 2
                    val candleWidth = (chartWidth / data.size) * scale

                    // 计算点击位置对应的K线索引
                    val clickX = offset.x - padding - offsetX
                    val index = (clickX / candleWidth).toInt()

                    if (index in data.indices) {
                        selectedIndex = index
                        // 计算K线中心的Offset
                        val candleCenterX = padding + offsetX + index * candleWidth + candleWidth / 2
                        val candleCenterY = size.height / 2f
                        onCandleSelected?.invoke(data[index], Offset(candleCenterX, candleCenterY))
                    } else {
                        selectedIndex = -1
                    }
                }
            }
    ) {
        val padding = 50f
        val chartWidth = size.width - padding * 2
        val chartHeight = size.height - padding * 2

        // 计算可见范围
        val candleWidth = (chartWidth / data.size) * scale
        val visibleStartIndex = max(0, ((-offsetX) / candleWidth).toInt())
        val visibleEndIndex = min(data.size, visibleStartIndex + (chartWidth / candleWidth).toInt() + 2)

        // 计算可见范围内的最高最低价
        val visibleData = if (visibleStartIndex < visibleEndIndex) {
            data.subList(visibleStartIndex, visibleEndIndex)
        } else {
            data
        }

        if (visibleData.isEmpty()) return@Canvas

        var highest = visibleData.first().high
        var lowest = visibleData.first().low
        visibleData.forEach { candle ->
            highest = max(highest, candle.high)
            lowest = min(lowest, candle.low)
        }

        val priceRange = highest - lowest
        if (priceRange == 0f) return@Canvas

        // 添加10%的上下边距
        val margin = priceRange * 0.1f
        highest += margin
        lowest -= margin
        val adjustedRange = highest - lowest

        // 绘制坐标轴
        drawLine(
            color = lineColor,
            start = Offset(padding, padding),
            end = Offset(padding, size.height - padding),
            strokeWidth = 2f
        )
        drawLine(
            color = lineColor,
            start = Offset(padding, size.height - padding),
            end = Offset(size.width - padding, size.height - padding),
            strokeWidth = 2f
        )

        // 绘制价格网格线
        val priceGridCount = 5
        for (i in 0..priceGridCount) {
            val y = padding + (chartHeight / priceGridCount) * i

            drawLine(
                color = lineColor.copy(alpha = 0.2f),
                start = Offset(padding, y),
                end = Offset(size.width - padding, y),
                strokeWidth = 1f
            )
        }

        // 计算最大成交量用于归一化
        val maxVolume = visibleData.maxOfOrNull { it.vol } ?: 1f
        val volumeBarHeight = chartHeight * 0.2f // 成交量占用图表高度的20%
        val volumeBaseY = size.height - padding

        // 绘制K线和成交量
        data.forEachIndexed { index, candle ->
            val x = padding + offsetX + index * candleWidth

            // 跳过不可见的K线
            if (x + candleWidth < padding || x > size.width - padding) return@forEachIndexed

            val isUp = candle.close >= candle.open
            val color = if (isUp) upColor else downColor

            // 检查是否有符号标记（通过日期匹配）
            val symbol = dateSymbolMap[candle.date]

            // 绘制选中背景
            if (index == selectedIndex) {
                drawRect(
                    color = selectedColor,
                    topLeft = Offset(x, padding),
                    size = Size(candleWidth, chartHeight)
                )
            }

            // 绘制符号背景（如果有符号标记）
            if (symbol != null) {
                val bgColor = symbolBackgroundColors[symbol.type::class]
                    ?: Color.Transparent
                drawRect(
                    color = bgColor,
                    topLeft = Offset(x, padding),
                    size = Size(candleWidth, chartHeight)
                )
            }

            // 计算Y坐标（调整以留出成交量空间）
            val priceChartHeight = chartHeight - volumeBarHeight - 10f
            val highY = padding + priceChartHeight - ((candle.high - lowest) / adjustedRange) * priceChartHeight
            val lowY = padding + priceChartHeight - ((candle.low - lowest) / adjustedRange) * priceChartHeight
            val openY = padding + priceChartHeight - ((candle.open - lowest) / adjustedRange) * priceChartHeight
            val closeY = padding + priceChartHeight - ((candle.close - lowest) / adjustedRange) * priceChartHeight

            // 绘制上下影线
            val centerX = x + candleWidth / 2
            drawLine(
                color = color,
                start = Offset(centerX, highY),
                end = Offset(centerX, lowY),
                strokeWidth = max(1f, candleWidth * 0.1f)
            )

            // 绘制实体
            val bodyTop = min(openY, closeY)
            val bodyBottom = max(openY, closeY)
            val bodyHeight = max(abs(closeY - openY), 1f)
            val bodyWidth = max(candleWidth * 0.7f, 1f)

            drawRect(
                color = color,
                topLeft = Offset(x + candleWidth * 0.15f, bodyTop),
                size = Size(bodyWidth, bodyHeight)
            )

            // 绘制成交量柱状图
            val normalizedVolume = candle.vol / maxVolume
            val volumeHeight = volumeBarHeight * normalizedVolume
            val volumeColor = color.copy(alpha = 0.5f)

            drawRect(
                color = volumeColor,
                topLeft = Offset(x + candleWidth * 0.15f, volumeBaseY - volumeHeight),
                size = Size(bodyWidth, volumeHeight)
            )
        }
    }
}
