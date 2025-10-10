package org.shiroumi.quant_kmp.toast

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Toast 的宿主 Composable。
 * 它监听状态，并使用动画来显示和隐藏 Toast。
 * @param hostState Toast 状态管理器
 * @param modifier Modifier
 * @param alignment Toast 在屏幕上的对齐方式，默认为底部居中
 */
@Composable
fun ToastHost(
    hostState: ToastHostState,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomCenter
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
 * Toast 的具体 UI 实现，遵循 Material 3 风格。
 * @param message 要显示的消息
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


// Toast 数据的模型，使用 UUID 确保每个 Toast 的唯一性
class ToastData @OptIn(ExperimentalUuidApi::class) constructor(
    val message: String,
    val id: String = Uuid.random().toString()
)

// Toast 状态管理器
class ToastHostState {
    // 当前显示的 Toast 数据
    var currentToastData by mutableStateOf<ToastData?>(null)
        private set

    private var toastJob: Job? = null

    /**
     * 显示一个 Toast。
     * 如果当前已有 Toast 在显示，它将被新的 Toast 替换。
     * @param message 要显示的消息
     * @param durationMillis 显示的持续时间（毫秒）
     * @param scope 用于启动协程以管理 Toast 的生命周期
     */
    suspend fun showToast(
        message: String,
        durationMillis: Long = 3000L,
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

// 用于在 Composable 中创建和记住 ToastHostState 实例的辅助函数
@Composable
fun rememberToastHostState(): ToastHostState {
    return remember { ToastHostState() }
}