package org.shiroumi.quant_kmp.ui.agent.sidebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.shiroumi.quant_kmp.ui.agent.state.AgentContract

/**
 * 减速贝塞尔曲线 — 干脆、迅速、优雅，无弹跳
 *
 * 匹配 Material Motion 的 "decelerate" 曲线：
 * 起始速度快，结尾平滑减速到零。
 */
private val DecelerateEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

/** 侧边栏展开/折叠动画时长 */
private const val EXPAND_DURATION_MS = 250

/**
 * AgentSidebar 容器组件
 *
 * 一个常驻右侧的可折叠边栏容器，提供：
 * - 展开/折叠两种状态切换（减速贝塞尔曲线动画）
 * - 拖拽调整宽度功能
 * - 处理中旋转指示器
 * - 未读消息红点提示
 *
 * 此组件为纯容器，不负责业务逻辑。业务内容通过 [content] 参数注入。
 */
@Composable
fun AgentSidebar(
    isVisible: Boolean,
    isExpanded: Boolean,
    isProcessing: Boolean = false,
    connectionStatus: AgentContract.ConnectionStatus = AgentContract.ConnectionStatus.DISCONNECTED,
    unreadCount: Int = 0,
    customWidth: Int? = null,
    collapseToZero: Boolean = false,
    onToggleExpand: () -> Unit,
    onWidthChange: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (!isVisible) return

    // 宽度定义
    val collapsedWidth = if (collapseToZero) 0.dp else 48.dp
    val standardWidth = 600.dp

    val targetWidth = if (isExpanded) standardWidth else collapsedWidth

    // 核心宽度动画 — 减速贝塞尔，无弹跳
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(
            durationMillis = EXPAND_DURATION_MS,
            easing = DecelerateEasing
        ),
        label = "sidebar_width"
    )

    // 内容透明度动画 — 展开时淡入，折叠时淡出
    val contentAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isExpanded) EXPAND_DURATION_MS else (EXPAND_DURATION_MS * 0.6).toInt(),
            easing = DecelerateEasing
        ),
        label = "content_alpha"
    )

    if (collapseToZero && animatedWidth < 1.dp) return

    Surface(
        modifier = modifier
            .width(animatedWidth)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = if (isExpanded) {
                    Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)
                } else {
                    Modifier.fillMaxSize()
                }
            ) {
                if (isExpanded || contentAlpha > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .graphicsLayer { alpha = contentAlpha }
                    ) {
                        content()
                    }
                } else {
                    SidebarTitleBar(
                        isProcessing = isProcessing,
                        unreadCount = unreadCount,
                        onToggleExpand = onToggleExpand
                    )
                }
            }

        }
    }
}

/**
 * 重新设计的标题栏
 *
 * 折叠态下的极简入口。
 */
@Composable
private fun SidebarTitleBar(
    isProcessing: Boolean,
    unreadCount: Int,
    onToggleExpand: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onToggleExpand,
            modifier = Modifier.size(40.dp)
        ) {
            BadgedBox(
                badge = {
                    if (unreadCount > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(8.dp)
                        )
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = "展开侧边栏",
                    modifier = Modifier.size(22.dp),
                    tint = if (isProcessing)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 内容切换动画包装器
 */
@Composable
fun AgentSidebarContentWrapper(
    contentKey: Any,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(EXPAND_DURATION_MS, easing = DecelerateEasing)),
        exit = fadeOut(tween(EXPAND_DURATION_MS, easing = DecelerateEasing)),
        modifier = modifier,
        label = "content_wrapper"
    ) {
        content()
    }
}

/**
 * 处理中指示器组件（独立使用）
 */
@Composable
fun ProcessingIndicator(
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isProcessing) return

    CircularProgressIndicator(
        modifier = modifier.size(20.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary
    )
}
