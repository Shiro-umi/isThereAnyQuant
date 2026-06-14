package org.shiroumi.quant_kmp.ui.agent.sidebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import model.candle.StockInfo
import org.shiroumi.quant_kmp.model.ChatMessage
import org.shiroumi.quant_kmp.ui.agent.components.AgentChatBubble
import org.shiroumi.quant_kmp.ui.agent.state.AgentContract
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme

private val SidebarSectionSpacing = 16.dp
private val SidebarCardPadding = 16.dp
internal const val STATUS_IDLE_OUTER_DURATION_MS = 1800
internal const val STATUS_BUSY_OUTER_DURATION_MS = 600
internal const val STATUS_IDLE_INNER_DELAY_MS = 150
internal const val STATUS_BUSY_INNER_DELAY_MS = 75

@Composable
fun AgentSidebarContent(
    state: AgentContract.State,
    selectedStock: StockInfo? = null,
    onSendMessage: (String?) -> Unit,
    onUpdateInput: (String) -> Unit,
    onApproveTool: (String) -> Unit,
    onRejectTool: (String) -> Unit,
    onStopAgent: (() -> Unit)? = null,
    onResumeAgent: (() -> Unit)? = null,
    onCollapse: (() -> Unit)? = null,
    onConnect: (() -> Unit)? = null,
    onNewSession: (() -> Unit)? = null,
    isCompactExpanded: Boolean = false,
    effect: Flow<AgentContract.Effect>? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // 收到 ScrollToBottom 即把对话滚到底（reverseLayout=true，底部对应 index 0）。
    // 由本组件持有 listState，故就近落地滚动，避免把 listState 提升到三个调用点。
    if (effect != null) {
        LaunchedEffect(effect) {
            effect.collect { signal ->
                if (signal is AgentContract.Effect.ScrollToBottom) {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(SidebarSectionSpacing)
    ) {
        AgentStatusSummaryCard(
            connectionStatus = state.connectionStatus,
            isProcessing = state.isProcessing,
            pendingApprovalCount = state.pendingApprovals.size,
            unreadCount = state.messages.count { it.role == "assistant" && !it.isThinking && it.content.isNotBlank() },
            onCollapse = onCollapse,
            onConnect = onConnect,
            onNewSession = onNewSession
        )

        if (isCompactExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AgentContextPresets(
                        selectedStock = selectedStock,
                        onPresetSelected = { preset ->
                            onUpdateInput(preset.prompt)
                            onSendMessage(preset.analysisType)
                        },
                        useTransparentBackground = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    ConversationWorkspaceCard(
                        messages = state.messages,
                        isProcessing = state.isProcessing,
                        connectionStatus = state.connectionStatus,
                        value = state.inputText,
                        onValueChange = onUpdateInput,
                        onSend = { onSendMessage(null) },
                        onStop = onStopAgent,
                        onResumeAgent = onResumeAgent,
                        onConnect = onConnect,
                        enabled = state.isInputEnabled && state.connectionStatus == AgentContract.ConnectionStatus.CONNECTED,
                        pendingApproval = state.pendingApprovals.firstOrNull(),
                        onApprove = onApproveTool,
                        onReject = onRejectTool,
                        listState = listState,
                        isCompactExpanded = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        } else {
            AgentContextPresets(
                selectedStock = selectedStock,
                onPresetSelected = { preset ->
                    onUpdateInput(preset.prompt)
                    onSendMessage(preset.analysisType)
                },
                useTransparentBackground = false,
                modifier = Modifier.fillMaxWidth()
            )

            ConversationWorkspaceCard(
                messages = state.messages,
                isProcessing = state.isProcessing,
                connectionStatus = state.connectionStatus,
                value = state.inputText,
                onValueChange = onUpdateInput,
                onSend = { onSendMessage(null) },
                onStop = onStopAgent,
                onResumeAgent = onResumeAgent,
                onConnect = onConnect,
                enabled = state.isInputEnabled && state.connectionStatus == AgentContract.ConnectionStatus.CONNECTED,
                pendingApproval = state.pendingApprovals.firstOrNull(),
                onApprove = onApproveTool,
                onReject = onRejectTool,
                listState = listState,
                isCompactExpanded = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun AgentStatusSummaryCard(
    connectionStatus: AgentContract.ConnectionStatus,
    isProcessing: Boolean,
    pendingApprovalCount: Int,
    unreadCount: Int,
    onCollapse: (() -> Unit)?,
    onConnect: (() -> Unit)? = null,
    onNewSession: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 14.dp, end = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            StatusIndicator(
                isProcessing = isProcessing,
                connectionStatus = connectionStatus
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = when (connectionStatus) {
                    AgentContract.ConnectionStatus.CONNECTED -> "Agent 已就绪"
                    AgentContract.ConnectionStatus.CONNECTING -> "Agent 连接中"
                    AgentContract.ConnectionStatus.ERROR -> "Agent 连接异常"
                    AgentContract.ConnectionStatus.DISCONNECTED -> "Agent 未连接"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = buildString {
                    append(if (isProcessing) "正在分析请求" else "可以开始新的对话")
                    if (pendingApprovalCount > 0) append(" · $pendingApprovalCount 个待确认")
                    if (unreadCount > 0) append(" · $unreadCount 条未读回复")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (connectionStatus == AgentContract.ConnectionStatus.ERROR && onConnect != null) {
                TextButton(
                    onClick = onConnect,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重试", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                StatusPill(
                    text = if (isProcessing) "进行中" else "待命",
                    containerColor = if (isProcessing) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (isProcessing) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (onNewSession != null) {
                IconButton(
                    onClick = onNewSession,
                    enabled = connectionStatus == AgentContract.ConnectionStatus.CONNECTED &&
                            !isProcessing &&
                            pendingApprovalCount == 0,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "新对话"
                    )
                }
            }
            if (onCollapse != null) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = "折叠侧边栏"
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationWorkspaceCard(
    messages: List<ChatMessage>,
    isProcessing: Boolean,
    connectionStatus: AgentContract.ConnectionStatus,
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: (() -> Unit)?,
    onResumeAgent: (() -> Unit)?,
    onConnect: (() -> Unit)? = null,
    enabled: Boolean,
    pendingApproval: AgentContract.PendingApproval?,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    listState: LazyListState,
    isCompactExpanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            AnimatedVisibility(
                visible = pendingApproval != null,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(120))
            ) {
                pendingApproval?.let {
                    ApprovalSection(
                        approval = it,
                        onApprove = onApprove,
                        onReject = onReject
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp)
            ) {
                if (messages.isEmpty()) {
                    EmptyConversationState(
                        connectionStatus = connectionStatus,
                        onConnect = onConnect,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    val reversedMessages = remember(messages) { messages.asReversed() }
                    if (isCompactExpanded) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            reverseLayout = true
                        ) {
                            itemsIndexed(
                                items = reversedMessages,
                                key = { _, message -> message.id }
                            ) { index, message ->
                                AgentChatBubble(
                                    message = message,
                                    isLastMessage = index == 0,
                                    onResume = onResumeAgent
                                )
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 0.dp
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                reverseLayout = true
                            ) {
                                itemsIndexed(
                                    items = reversedMessages,
                                    key = { _, message -> message.id }
                                ) { index, message ->
                                    AgentChatBubble(
                                        message = message,
                                        isLastMessage = index == 0
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ComposerSection(
                value = value,
                onValueChange = onValueChange,
                onSend = onSend,
                onStop = onStop,
                enabled = enabled,
                isProcessing = isProcessing
            )
        }
    }

    if (isCompactExpanded) {
        content()
    } else {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(AgentTheme.Shapes.large),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            content()
        }
    }
}

@Composable
private fun ApprovalSection(
    approval: AgentContract.PendingApproval,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SidebarCardPadding, vertical = 12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = null
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = approval.toolName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = approval.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onReject(approval.requestId) }) {
                    Text("拒绝")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onApprove(approval.requestId) }) {
                    Text("批准")
                }
            }
        }
    }
}

@Composable
private fun EmptyConversationState(
    connectionStatus: AgentContract.ConnectionStatus = AgentContract.ConnectionStatus.CONNECTED,
    onConnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (connectionStatus) {
                AgentContract.ConnectionStatus.ERROR -> {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "连接异常，请重试",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (onConnect != null) {
                        Button(onClick = onConnect) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("重新连接")
                        }
                    }
                }
                AgentContract.ConnectionStatus.DISCONNECTED -> {
                    Icon(
                        imageVector = Icons.Outlined.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Agent 未连接",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (onConnect != null) {
                        Button(onClick = onConnect) {
                            Icon(
                                imageVector = Icons.Outlined.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("启动 Agent 会话")
                        }
                    }
                }
                AgentContract.ConnectionStatus.CONNECTING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                    Text(
                        text = "正在建立连接...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AgentContract.ConnectionStatus.CONNECTED -> {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "开始与 Agent 对话",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerSection(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: (() -> Unit)? = null,
    enabled: Boolean,
    isProcessing: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SidebarCardPadding, end = SidebarCardPadding, bottom = SidebarCardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                        if (keyEvent.isShiftPressed || keyEvent.isAltPressed) {
                            onValueChange(value + "\n")
                            true
                        } else {
                            if (enabled && value.isNotBlank() && !isProcessing) {
                                onSend()
                            }
                            true
                        }
                    } else {
                        false
                    }
                },
            placeholder = {
                Text(
                    text = "输入消息，回车发送",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            enabled = enabled && !isProcessing,
            minLines = 1,
            maxLines = 2,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            shape = MaterialTheme.shapes.medium
        )

        if (isProcessing && onStop != null) {
            Button(
                onClick = onStop,
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "停止"
                )
            }
        } else {
            Button(
                onClick = onSend,
                enabled = enabled && value.isNotBlank() && !isProcessing,
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "发送"
                )
            }
        }
    }
}

/**
 * StatusIndicator 呼吸动画的当前缩放与透明度值
 */
internal data class StatusIndicatorScales(
    val outerScale: Float,
    val innerScale: Float,
    val outerAlpha: Float,
    val innerAlpha: Float
)

/**
 * 记住 StatusIndicator 的呼吸动画值，供外部（如 FAB）同步使用
 */
@Composable
internal fun rememberStatusIndicatorScales(
    isProcessing: Boolean,
    isConnected: Boolean
): StatusIndicatorScales {
    val transition = rememberInfiniteTransition(label = "agent_status_indicator")
    val outerScale by transition.animateFloat(
        initialValue = if (isProcessing) 0.88f else 0.96f,
        targetValue = if (isProcessing) 1.22f else 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isProcessing) STATUS_BUSY_OUTER_DURATION_MS else STATUS_IDLE_OUTER_DURATION_MS,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status_outer_scale"
    )
    val innerScale by transition.animateFloat(
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
        label = "status_inner_scale"
    )
    val outerAlpha by transition.animateFloat(
        initialValue = if (isProcessing) 0.18f else if (isConnected) 0.24f else 0.14f,
        targetValue = if (isProcessing) 0.40f else if (isConnected) 0.32f else 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isProcessing) STATUS_BUSY_OUTER_DURATION_MS else STATUS_IDLE_OUTER_DURATION_MS,
                easing = LinearOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status_outer_alpha"
    )
    val innerAlpha by transition.animateFloat(
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
        label = "status_inner_alpha"
    )
    return StatusIndicatorScales(outerScale, innerScale, outerAlpha, innerAlpha)
}

@Composable
internal fun StatusIndicator(
    isProcessing: Boolean,
    connectionStatus: AgentContract.ConnectionStatus,
    modifier: Modifier = Modifier.fillMaxSize(),
    scales: StatusIndicatorScales? = null
) {
    val isConnected = connectionStatus == AgentContract.ConnectionStatus.CONNECTED
    val color = when {
        isProcessing -> MaterialTheme.colorScheme.tertiary
        isConnected -> MaterialTheme.colorScheme.primary
        connectionStatus == AgentContract.ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.secondary
        connectionStatus == AgentContract.ConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    val actualScales = scales ?: rememberStatusIndicatorScales(isProcessing, isConnected)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = actualScales.outerScale
                    scaleY = actualScales.outerScale
                    alpha = actualScales.outerAlpha
                }
                .clip(CircleShape)
                .background(color)
        )

        Box(
            modifier = Modifier
                .fillMaxSize(0.3f)
                .graphicsLayer {
                    scaleX = actualScales.innerScale
                    scaleY = actualScales.innerScale
                    alpha = actualScales.innerAlpha
                }
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun StatusPill(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
