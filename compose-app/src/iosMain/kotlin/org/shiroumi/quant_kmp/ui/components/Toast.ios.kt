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
 * iOS 平台的 ToastHostState 实现。
 * iOS 无系统级 Toast，与 Web 一致使用 Compose 自绘。
 */
actual class ToastHostState {
    var currentToastData by mutableStateOf<ToastData?>(null)
        private set

    private var toastJob: Job? = null

    actual suspend fun showToast(
        message: String,
        durationMillis: Long
    ) = coroutineScope {
        toastJob?.cancel()

        val newToastData = ToastData(message)
        currentToastData = newToastData

        toastJob = launch {
            delay(durationMillis)
            if (currentToastData?.id == newToastData.id) {
                currentToastData = null
            }
        }
    }
}

/**
 * iOS 平台的 ToastHost 实现，遵循 Material 3 风格与统一减速动画。
 */
@Composable
actual fun ToastHost(
    hostState: ToastHostState,
    modifier: Modifier,
    alignment: Alignment
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = alignment
    ) {
        AnimatedVisibility(
            visible = hostState.currentToastData != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            val message = hostState.currentToastData?.message ?: ""
            ToastUI(message = message)
        }
    }
}

@Composable
private fun ToastUI(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
actual fun rememberToastHostState(): ToastHostState {
    return remember { ToastHostState() }
}
