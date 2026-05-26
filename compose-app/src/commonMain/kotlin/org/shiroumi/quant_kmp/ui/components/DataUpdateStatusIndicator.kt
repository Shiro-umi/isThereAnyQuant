package org.shiroumi.quant_kmp.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import model.DataUpdateStatus
import org.shiroumi.quant_kmp.service.DataUpdateService
import org.shiroumi.quant_kmp.service.formatDateTime
import org.shiroumi.quant_kmp.service.formatDuration
import org.shiroumi.quant_kmp.ui.theme.quantColors
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * 数据更新状态指示器
 * 显示在TopAppBar右侧
 */
@Composable
fun DataUpdateStatusIndicator(
    modifier: Modifier = Modifier,
    centered: Boolean = false
) {
    val service = remember { DataUpdateService.getInstance() }
    val status by service.status.collectAsState()
    val shouldShow by service.shouldShowIndicator.collectAsState()

    // 启动服务
    DisposableEffect(Unit) {
        service.start()
        onDispose { service.stop() }
    }

    AnimatedVisibility(
        visible = shouldShow,
        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing))
    ) {
        Box(modifier = modifier) {
            when (val currentStatus = status) {
                is DataUpdateStatus -> {
                    when (currentStatus.state) {
                        DataUpdateStatus.STATE_UPDATING -> {
                            UpdatingIndicator(
                                startTime = currentStatus.currentUpdateStartTime ?: 0,
                                step = currentStatus.currentStep,
                                progress = currentStatus.progress,
                                centered = centered
                            )
                        }
                        DataUpdateStatus.STATE_COMPLETED -> {
                            CompletedIndicator(centered = centered)
                        }
                        DataUpdateStatus.STATE_FAILED -> {
                            FailedIndicator(message = currentStatus.message, centered = centered)
                        }
                        else -> {
                            IdleIndicator(
                                lastUpdateTime = currentStatus.lastUpdateTime,
                                timeUntilNext = currentStatus.timeUntilNextUpdate ?: 0L,
                                centered = centered
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 更新中指示器
 */
@Composable
private fun UpdatingIndicator(
    startTime: Long,
    step: String,
    progress: Int,
    centered: Boolean
) {
    var elapsed by remember { mutableStateOf(0L) }
    
    // 每秒更新已用时间
    LaunchedEffect(startTime) {
        val start = Clock.System.now().toEpochMilliseconds()
        while (true) {
            elapsed = Clock.System.now().toEpochMilliseconds() - start
            delay(1000.milliseconds)
        }
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.width(6.dp))
        
        Column(
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
        ) {
            Text(
                text = "更新中 ${formatDuration(elapsed)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start
            )
            
            if (step.isNotEmpty()) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start
                )
            }
        }
    }
}

/**
 * 空闲状态指示器
 */
@Composable
private fun IdleIndicator(
    lastUpdateTime: Long?,
    timeUntilNext: Long,
    centered: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.quantColors.success
        )

        Spacer(modifier = Modifier.width(4.dp))
        
        Column(
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
        ) {
            // 显示最后更新时间
            lastUpdateTime?.let { time ->
                Text(
                    text = "数据: ${formatDateTime(time)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start
                )
            }
            
            // 显示距离下次更新时间
            if (timeUntilNext > 0) {
                Text(
                    text = "下次: ${formatDuration(timeUntilNext)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start
                )
            }
        }
    }
}

/**
 * 完成指示器
 * 显示/隐藏由外层 DataUpdateStatusIndicator 的 AnimatedVisibility 统一控制
 */
@Composable
private fun CompletedIndicator(centered: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.quantColors.success
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "更新完成",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.quantColors.success,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start
        )
    }
}

/**
 * 失败指示器
 */
@Composable
private fun FailedIndicator(message: String, centered: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "更新失败",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start
        )
    }
}

/**
 * 是否可以提交任务
 * 用于禁用提交按钮
 */
@Composable
fun rememberCanSubmitTask(): Boolean {
    val service = remember { DataUpdateService.getInstance() }
    val status by service.status.collectAsState()
    
    return status.canSubmitTask()
}

/**
 * 是否可以执行选股策略
 * 限制：数据正在更新时不能选股
 * 返回值: Pair<是否可以执行, 不可执行时的提示信息>
 */
@Composable
fun rememberCanRunStrategy(): Pair<Boolean, String?> {
    val service = remember { DataUpdateService.getInstance() }
    val status by service.status.collectAsState()
    
    // 如果正在更新，不能选股
    if (status.isUpdating()) {
        return false to "数据更新中，请稍后"
    }
    
    // 放宽限制：移除强制要求今日已更新的检查，以避免周末/节假日或服务端重启（lastUpdateTime 为 null）时无法选股的 Bug。
    return true to null
}
