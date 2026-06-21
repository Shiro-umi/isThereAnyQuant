package org.shiroumi.quant_kmp.ui.agent.sidebar

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.shiroumi.quant_kmp.ui.agent.state.AgentContract
import kotlin.math.roundToInt

// FAB 展开/收起的动画时序（绝对时间，独立计时）：
// - Y 轴位移：500ms 全程基准，展开/收起都 0ms 启动
// - X 轴位移：300ms，展开 0ms 启动；收起 100ms 启动
// - 共享边界动画：300ms，展开延后 100ms；从 48dp FAB 本体直接形变到展开卡片，触摸外框不参与形变
private const val Y_AXIS_DURATION_MS = 500
private const val X_AXIS_DURATION_MS = 300
private const val X_AXIS_COLLAPSE_DELAY_MS = 100
private const val SHARED_DURATION_MS = 300
private const val SHARED_EXPAND_DELAY_MS = 100

/**
 * 移动端 Agent 浮动卡片
 *
 * - 折叠态：可拖动的 FAB
 * - 展开态：全屏覆盖（带非线性 SharedElement 动画）
 */
@Composable
fun AgentFloatingCard(
    isExpanded: Boolean,
    isProcessing: Boolean,
    connectionStatus: AgentContract.ConnectionStatus,
    unreadCount: Int,
    onToggleExpand: () -> Unit,
    onConnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current

    val currentConnectionStatus by rememberUpdatedState(connectionStatus)
    val currentOnToggleExpand by rememberUpdatedState(onToggleExpand)
    val currentOnConnect by rememberUpdatedState(onConnect)

    // FAB 触摸区域尺寸（紧凑型，适合手机和平板）。
    // 触摸框比内部 48dp 视觉 Surface 大一圈，四周留白用于承载阴影（shadowElevation 溢出）
    // 与呼吸放大（breathOuterScale 最大 1.22×），避免 FAB 动画时阴影/光晕被容器边界裁切。
    val fabTouchSize = 76.dp
    val fabTouchSizePx = with(density) { fabTouchSize.toPx() }
    val fabPaddingPx = with(density) { 16.dp.toPx() }

    // FAB 拖动位置（相对于屏幕右下角，单位：px）
    // 初始位置：紧贴右下角，四周留出 16.dp 边距
    var fabOffset by remember(fabTouchSizePx, fabPaddingPx) {
        mutableStateOf(
            Offset(
                -(fabTouchSizePx + fabPaddingPx),
                -(fabTouchSizePx + fabPaddingPx * 2)
            )
        )
    }
    var screenSize by remember { mutableStateOf(IntOffset.Zero) }

    // FAB 呼吸动画：直接在 AgentFloatingCard 内创建，确保重组时尺寸同步更新
    val isConnected = connectionStatus == AgentContract.ConnectionStatus.CONNECTED
    val breathTransition = rememberInfiniteTransition(label = "fab_breath")
    val breathOuterScale by breathTransition.animateFloat(
        initialValue = if (isProcessing) 0.88f else 0.96f,
        targetValue = if (isProcessing) 1.22f else 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isProcessing) STATUS_BUSY_OUTER_DURATION_MS else STATUS_IDLE_OUTER_DURATION_MS,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fab_breath_outer_scale"
    )
    val breathInnerScale by breathTransition.animateFloat(
        initialValue = if (isProcessing) 0.78f else 0.92f,
        targetValue = if (isProcessing) 1.28f else 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isProcessing) STATUS_BUSY_OUTER_DURATION_MS else STATUS_IDLE_OUTER_DURATION_MS,
                delayMillis = if (isProcessing) STATUS_BUSY_INNER_DELAY_MS else STATUS_IDLE_INNER_DELAY_MS,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fab_breath_inner_scale"
    )
    val breathOuterAlpha by breathTransition.animateFloat(
        initialValue = if (isProcessing) 0.18f else if (isConnected) 0.24f else 0.14f,
        targetValue = if (isProcessing) 0.40f else if (isConnected) 0.32f else 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isProcessing) STATUS_BUSY_OUTER_DURATION_MS else STATUS_IDLE_OUTER_DURATION_MS,
                easing = LinearOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fab_breath_outer_alpha"
    )
    val breathInnerAlpha by breathTransition.animateFloat(
        initialValue = if (isProcessing) 0.82f else if (isConnected) 0.92f else 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isProcessing) STATUS_BUSY_OUTER_DURATION_MS else STATUS_IDLE_OUTER_DURATION_MS,
                delayMillis = if (isProcessing) STATUS_BUSY_INNER_DELAY_MS else STATUS_IDLE_INNER_DELAY_MS,
                easing = LinearOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fab_breath_inner_alpha"
    )
    val fabIndicatorScales = StatusIndicatorScales(
        outerScale = breathOuterScale.coerceAtLeast(1f),
        innerScale = breathInnerScale,
        outerAlpha = breathOuterAlpha,
        innerAlpha = breathInnerAlpha
    )
    val fabBaseSize = 48.dp
    val fabBaseSizePx = with(density) { fabBaseSize.toPx() }

    // 统一减速曲线（CLAUDE.md：非线性动画一律减速型，禁止弹跳/回弹）。
    val axisEasing = remember { CubicBezierEasing(0.0f, 0.0f, 0.2f, 1f) }
    // 位移两轴共用同一 easing：展开用统一减速曲线，收起用系统减速曲线。
    val axisDisplacementEasing = if (isExpanded) axisEasing else LinearOutSlowInEasing

    // 位移 Y 轴：500ms 全程，展开/收起都 0ms 启动。
    val expandProgressY by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = Y_AXIS_DURATION_MS,
            easing = axisDisplacementEasing
        ),
        label = "expand_progress_y"
    )
    // 位移 X 轴更快，展开 0s 启动（起点对齐 Y）；收起延后启动（终点对齐 Y）。
    val xAxisDelay = if (isExpanded) 0 else X_AXIS_COLLAPSE_DELAY_MS
    val expandProgressX by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = X_AXIS_DURATION_MS,
            delayMillis = xAxisDelay,
            easing = axisDisplacementEasing
        ),
        label = "expand_progress_x"
    )
    // 共享边界动画（FAB 本体→展开卡片 + 内容交叉淡变 + 背景 + 圆角）：独立计时，与位移 easing 解耦。
    val sharedProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = SHARED_DURATION_MS,
            delayMillis = if (isExpanded) SHARED_EXPAND_DELAY_MS else 0,
            easing = axisEasing
        ),
        label = "shared_progress"
    )

    // 内容交叉淡变、背景/圆角/阴影等共享边界动画统一以 sharedProgress 为基准。
    // 形变从 48dp FAB 视觉面开始，76dp 触摸外框只负责折叠态手势与阴影/光晕溢出。
    val currentWidthPx = fabBaseSizePx + (screenSize.x - fabBaseSizePx) * sharedProgress
    val currentHeightPx = fabBaseSizePx + (screenSize.y - fabBaseSizePx) * sharedProgress
    val currentWidth = with(density) { currentWidthPx.toDp() }
    val currentHeight = with(density) { currentHeightPx.toDp() }

    // 位移分轴：X/Y 各自插值组件中心点，再按当前尺寸反推左上角。
    // 动作终点是可用空间中心，因此每个轴的最大动作范围天然是对应可用空间的一半。
    val collapsedTouchX = screenSize.x + fabOffset.x
    val collapsedTouchY = screenSize.y + fabOffset.y
    val collapsedCenterX = collapsedTouchX + fabTouchSizePx / 2f
    val collapsedCenterY = collapsedTouchY + fabTouchSizePx / 2f
    val expandedCenterX = screenSize.x / 2f
    val expandedCenterY = screenSize.y / 2f
    val currentCenterX = collapsedCenterX + (expandedCenterX - collapsedCenterX) * expandProgressX
    val currentCenterY = collapsedCenterY + (expandedCenterY - collapsedCenterY) * expandProgressY
    val currentX = currentCenterX - currentWidthPx / 2f
    val currentY = currentCenterY - currentHeightPx / 2f

    // 圆角随共享边界动画走：展开 24dp(FAB 本体纯圆) → 0dp(卡片直角)，收起反向，全程平滑。
    val cornerRadius = androidx.compose.ui.unit.lerp(
        24.dp,
        0.dp,
        sharedProgress
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { screenSize = IntOffset(it.width, it.height) }
    ) {
        // 背景遮罩
        if (sharedProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f * sharedProgress))
                    .zIndex(9f)
                    .pointerInput(Unit) {
                        detectDragGestures { _, _ -> }
                    }
            )
        }

        // FAB / 卡片
        val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
        val sharedContainerColor = lerpColor(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.background,
            sharedProgress
        )
        val sharedElevationProgress = 1f - sharedProgress
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
                .size(currentWidth, currentHeight)
                .zIndex(10f)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val breathScale = if (sharedProgress == 0f) breathOuterScale else 1f
                        scaleX = breathScale
                        scaleY = breathScale
                    },
                shape = cardShape,
                color = sharedContainerColor,
                contentColor = MaterialTheme.colorScheme.onBackground,
                tonalElevation = 3.dp * sharedElevationProgress,
                shadowElevation = 6.dp * sharedElevationProgress
            ) {
                // FAB 内容
                if (sharedProgress < 0.5f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 1f - sharedProgress * 2f },
                        contentAlignment = Alignment.Center
                    ) {
                        BadgedBox(
                            badge = {
                                if (unreadCount > 0) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.offset(x = 8.dp, y = (-8).dp)
                                    ) {
                                        Text(
                                            text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        ) {
                            // 视觉共享边界是外层 Surface；这里仅渲染 FAB 内部状态内容。
                            Box(
                                modifier = Modifier
                                    .size(fabBaseSize),
                                contentAlignment = Alignment.Center
                            ) {
                                StatusIndicator(
                                    isProcessing = isProcessing,
                                    connectionStatus = connectionStatus,
                                    modifier = Modifier.fillMaxSize(),
                                    scales = fabIndicatorScales
                                )
                                val showRetryIcon = connectionStatus == AgentContract.ConnectionStatus.ERROR ||
                                        connectionStatus == AgentContract.ConnectionStatus.DISCONNECTED
                                if (showRetryIcon) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = "重新连接",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onError
                                    )
                                }
                            }
                        }
                    }
                }

                // 卡片内容
                if (sharedProgress > 0.3f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = (sharedProgress - 0.3f) / 0.7f }
                    ) {
                        content()
                    }
                }
            }
        }

        if (!isExpanded && sharedProgress < 0.5f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(collapsedTouchX.roundToInt(), collapsedTouchY.roundToInt()) }
                    .size(fabTouchSize)
                    .zIndex(11f)
                    .semantics {
                        role = androidx.compose.ui.semantics.Role.Button
                        contentDescription = "Agent 浮动按钮"
                    }
                    .pointerInput(isExpanded) {
                        if (!isExpanded) {
                            val touchSlop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var totalDrag = Offset.Zero
                                var isDragging = false
                                drag(down.id) { change ->
                                    val delta = change.positionChange()
                                    totalDrag += delta
                                    if (!isDragging && totalDrag.getDistance() > touchSlop) {
                                        isDragging = true
                                    }
                                    if (isDragging) {
                                        change.consume()
                                        val screenWidth = screenSize.x.toFloat()
                                        val screenHeight = screenSize.y.toFloat()
                                        fabOffset = Offset(
                                            (fabOffset.x + delta.x).coerceIn(
                                                -screenWidth + fabPaddingPx,
                                                -fabTouchSizePx
                                            ),
                                            (fabOffset.y + delta.y).coerceIn(
                                                -screenHeight + fabPaddingPx,
                                                -(fabTouchSizePx + fabPaddingPx)
                                            )
                                        )
                                    }
                                }
                                if (!isDragging) {
                                    val isFailed = currentConnectionStatus == AgentContract.ConnectionStatus.ERROR ||
                                            currentConnectionStatus == AgentContract.ConnectionStatus.DISCONNECTED
                                    if (isFailed) currentOnConnect?.invoke() else currentOnToggleExpand()
                                }
                            }
                        }
                    }
            )
        }
    }
}
