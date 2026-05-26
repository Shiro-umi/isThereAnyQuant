package org.shiroumi.quant_kmp.ui.agent.sidebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.shiroumi.quant_kmp.ui.agent.state.AgentContract
import kotlin.math.roundToInt

private val DecelerateEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
private const val OVERLAY_DURATION_MS = 250
private val OVERLAY_WIDTH = 600.dp

@Composable
fun AgentDesktopOverlay(
    isExpanded: Boolean,
    isProcessing: Boolean,
    connectionStatus: AgentContract.ConnectionStatus,
    unreadCount: Int,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onConnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val currentConnectionStatus by rememberUpdatedState(connectionStatus)
    val currentOnExpand by rememberUpdatedState(onExpand)
    val currentOnConnect by rememberUpdatedState(onConnect)
    val fabTouchSize = 60.dp
    val fabTouchSizePx = with(density) { fabTouchSize.toPx() }
    val fabPaddingPx = with(density) { 16.dp.toPx() }

    var fabOffset by remember(fabTouchSizePx, fabPaddingPx) {
        mutableStateOf(
            Offset(
                -(fabTouchSizePx + fabPaddingPx),
                -(fabTouchSizePx + fabPaddingPx * 2)
            )
        )
    }
    var containerSize by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = IntOffset(it.width, it.height) }
    ) {
        // FAB — 可拖动，仅在 overlay 未展开时显示
        if (!isExpanded && containerSize.x > 0) {
            val fabX = containerSize.x + fabOffset.x.roundToInt()
            val fabY = containerSize.y + fabOffset.y.roundToInt()

            Box(
                modifier = Modifier
                    .offset { IntOffset(fabX, fabY) }
                    .size(fabTouchSize)
                    .zIndex(10f)
                    .pointerInput(Unit) {
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
                                    val w = containerSize.x.toFloat()
                                    val h = containerSize.y.toFloat()
                                    fabOffset = Offset(
                                        (fabOffset.x + delta.x).coerceIn(
                                            -w + fabPaddingPx,
                                            -fabTouchSizePx
                                        ),
                                        (fabOffset.y + delta.y).coerceIn(
                                            -h + fabPaddingPx,
                                            -(fabTouchSizePx + fabPaddingPx)
                                        )
                                    )
                                }
                            }
                            if (!isDragging) {
                                val isFailed = currentConnectionStatus == AgentContract.ConnectionStatus.ERROR ||
                                        currentConnectionStatus == AgentContract.ConnectionStatus.DISCONNECTED
                                if (isFailed) currentOnConnect?.invoke() else currentOnExpand()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                DesktopAgentFab(
                    isProcessing = isProcessing,
                    connectionStatus = connectionStatus,
                    unreadCount = unreadCount
                )
            }
        }

        // Scrim
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(OVERLAY_DURATION_MS, easing = DecelerateEasing)),
            exit = fadeOut(tween(OVERLAY_DURATION_MS, easing = DecelerateEasing))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    .pointerInput(Unit) {
                        detectTapGestures { onCollapse() }
                    }
            )
        }

        // Sidebar overlay panel
        AnimatedVisibility(
            visible = isExpanded,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(OVERLAY_DURATION_MS, easing = DecelerateEasing)
            ) + fadeIn(tween(OVERLAY_DURATION_MS, easing = DecelerateEasing)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(OVERLAY_DURATION_MS, easing = DecelerateEasing)
            ) + fadeOut(tween(OVERLAY_DURATION_MS, easing = DecelerateEasing)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                modifier = Modifier
                    .width(OVERLAY_WIDTH)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 8.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun DesktopAgentFab(
    isProcessing: Boolean,
    connectionStatus: AgentContract.ConnectionStatus,
    unreadCount: Int
) {
    val isConnected = connectionStatus == AgentContract.ConnectionStatus.CONNECTED
    val scales = rememberStatusIndicatorScales(isProcessing, isConnected)
    val fabBaseSize = 48.dp

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
        Surface(
            modifier = Modifier
                .size(fabBaseSize)
                .graphicsLayer {
                    scaleX = scales.outerScale
                    scaleY = scales.outerScale
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
                    scales = scales
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
