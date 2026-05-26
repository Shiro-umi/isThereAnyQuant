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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

    // FAB 触摸区域尺寸（紧凑型，适合手机和平板）
    val fabTouchSize = 60.dp
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

    // 展开与收起使用不同的贝塞尔曲线：展开时 X/Y 交换，收起保持原样
    val easingCollapseX = remember { CubicBezierEasing(0.0f, 0.0f, 0.2f, 1f) }
    val easingCollapseY = remember { CubicBezierEasing(0.0f, 0.0f, 0.0f, 1f) }
    val easingExpandX = remember { CubicBezierEasing(0.0f, 0.0f, 0.0f, 1f) }
    val easingExpandY = remember { CubicBezierEasing(0.0f, 0.0f, 0.2f, 1f) }

    val expandProgressX by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = 280,
            easing = if (isExpanded) easingExpandX else easingCollapseX
        ),
        label = "expand_progress_x"
    )
    val expandProgressY by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = 280,
            easing = if (isExpanded) easingExpandY else easingCollapseY
        ),
        label = "expand_progress_y"
    )

    // 内容切换以 X 轴进度为基准
    val expandProgress = expandProgressX

    val currentWidth = with(density) {
        androidx.compose.ui.unit.lerp(
            fabTouchSize,
            screenSize.x.toDp(),
            expandProgressX
        )
    }

    val currentHeight = with(density) {
        androidx.compose.ui.unit.lerp(
            fabTouchSize,
            screenSize.y.toDp(),
            expandProgressY
        )
    }

    val currentX = with(density) {
        androidx.compose.ui.unit.lerp(
            (screenSize.x + fabOffset.x.roundToInt()).toDp(),
            0.dp,
            expandProgressX
        )
    }

    val currentY = with(density) {
        androidx.compose.ui.unit.lerp(
            (screenSize.y + fabOffset.y.roundToInt()).toDp(),
            0.dp,
            expandProgressY
        )
    }

    val cornerRadius = androidx.compose.ui.unit.lerp(
        30.dp,
        0.dp,
        expandProgressX
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { screenSize = IntOffset(it.width, it.height) }
    ) {
        // 背景遮罩
        if (expandProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f * expandProgress))
                    .zIndex(9f)
                    .pointerInput(Unit) {
                        detectDragGestures { _, _ -> }
                    }
            )
        }

        // FAB / 卡片
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(currentX.roundToPx(), currentY.roundToPx()) }
                .size(currentWidth, currentHeight)
                .zIndex(10f)
                .then(
                    if (expandProgress >= 1f) {
                        Modifier.background(
                            color = MaterialTheme.colorScheme.background,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
                        )
                    } else Modifier
                )
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
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // FAB 内容
                if (expandProgress < 0.5f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 1f - expandProgress * 2f },
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
                            // 自定义 FAB（外层 Surface 已承担 onClick / drag / sharedTransition 变形动画），
                            // 视觉对齐 M3 SmallFloatingActionButton：48dp + CircleShape + surfaceContainerHigh
                            // + tonalElevation=3 + shadowElevation=6。
                            Surface(
                                modifier = Modifier
                                    .size(fabBaseSize)
                                    .semantics {
                                        role = androidx.compose.ui.semantics.Role.Button
                                        contentDescription = "Agent 浮动按钮"
                                    }
                                    .graphicsLayer {
                                        scaleX = breathOuterScale
                                        scaleY = breathOuterScale
                                    },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 3.dp,
                                shadowElevation = 6.dp
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
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
                }

                // 卡片内容
                if (expandProgress > 0.3f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = (expandProgress - 0.3f) / 0.7f }
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
