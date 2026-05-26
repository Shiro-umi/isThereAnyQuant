package org.shiroumi.quant_kmp.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Toast 数据的模型，使用 UUID 确保每个 Toast 的唯一性
class ToastData @OptIn(ExperimentalUuidApi::class) constructor(
    val message: String,
    val id: String = Uuid.random().toString()
)

/**
 * Web 平台的 ToastHostState 实现
 */
actual class ToastHostState {
    // 当前显示的 Toast 数据
    var currentToastData by mutableStateOf<ToastData?>(null)
        private set

    private var toastJob: Job? = null

    /**
     * 显示一个 Toast
     * 如果当前已有 Toast 在显示，它将被新的 Toast 替换
     */
    actual suspend fun showToast(
        message: String,
        durationMillis: Long
    ) = coroutineScope {
        // 如果有正在进行的 Toast，先取消它
        toastJob?.cancel()

        // 创建新的 Toast 数据并启动新的协程
        val newToastData = ToastData(message)
        currentToastData = newToastData

        toastJob = launch {
            delay(durationMillis)
            // 延迟结束后，如果当前显示的还是这个 Toast，则将其隐藏
            if (currentToastData?.id == newToastData.id) {
                currentToastData = null
            }
        }
    }
}

/**
 * Web 平台的 ToastHost 实现
 * 使用 Compose UI 实现 Toast 效果
 */
@Composable
actual fun ToastHost(
    hostState: ToastHostState,
    modifier: Modifier,
    alignment: Alignment
) {
    // BoxWithConstraints 用于让 Toast 在可用空间内对齐
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp), // 留出屏幕边缘的安全边距
        contentAlignment = alignment
    ) {
        // AnimatedVisibility 提供了优雅的进入和退出动画
        AnimatedVisibility(
            visible = hostState.currentToastData != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            // 从 hostState 中获取消息
            val message = hostState.currentToastData?.message ?: ""
            ToastUI(message = message)
        }
    }
}

/**
 * Toast 的具体 UI 实现，遵循 Material 3 风格
 */
@Composable
private fun ToastUI(message: String) {
    Surface(
        // 使用与 Snackbar 类似的颜色
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.medium, // 圆角
        tonalElevation = 4.dp, // 轻微的海拔阴影
        shadowElevation = 4.dp
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium // M3 文本样式
        )
    }
}

/**
 * 创建和记住 ToastHostState 实例的辅助函数 - Web平台实现
 */
@Composable
actual fun rememberToastHostState(): ToastHostState {
    return remember { ToastHostState() }
}
