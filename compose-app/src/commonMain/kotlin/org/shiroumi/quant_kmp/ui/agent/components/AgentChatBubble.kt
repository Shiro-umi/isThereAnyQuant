package org.shiroumi.quant_kmp.ui.agent.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import model.ws.AgentInterruption
import model.ws.AgentInterruptionReason
import model.ws.AgentLogEntry
import model.ws.AgentLogType
import model.ws.ToolCallStatus
import org.shiroumi.quant_kmp.model.ChatMessage
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import org.shiroumi.quant_kmp.ui.components.BaseChatBubble
import org.shiroumi.quant_kmp.ui.components.MarkdownText
import org.shiroumi.quant_kmp.ui.markdown.AgentReportMarkdownText

private val DecelerateMotionEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
private const val EXPAND_MOTION_DURATION_MS = 320
private const val COLLAPSE_MOTION_DURATION_MS = 220

private fun expandMotionSpec() = tween<IntSize>(
    durationMillis = EXPAND_MOTION_DURATION_MS,
    easing = DecelerateMotionEasing
)

private fun collapseMotionSpec() = tween<IntSize>(
    durationMillis = COLLAPSE_MOTION_DURATION_MS,
    easing = DecelerateMotionEasing
)

private fun expandFloatMotionSpec(durationMillis: Int = EXPAND_MOTION_DURATION_MS) = tween<Float>(
    durationMillis = durationMillis,
    easing = DecelerateMotionEasing
)

private fun collapseFloatMotionSpec(durationMillis: Int = COLLAPSE_MOTION_DURATION_MS) = tween<Float>(
    durationMillis = durationMillis,
    easing = DecelerateMotionEasing
)

/**
 * 优雅的聊天消息气泡组件
 */
@Composable
fun AgentChatBubble(
    message: ChatMessage,
    isLastMessage: Boolean = false,
    modifier: Modifier = Modifier,
    onResume: (() -> Unit)? = null
) {
    val isUser = message.role == "user"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AgentTheme.Spacing.xs),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            MessageBubble(message = message, isUser = true)
        } else {
            // Agent message: Sequential logs without outer bubble
            AgentLogsSection(message = message, isLastMessage = isLastMessage)
            // 中断时浮现"继承上下文继续"按钮
            val interruption = message.interruption
            if (interruption != null && isLastMessage) {
                ResumeContinueCard(
                    interruption = interruption,
                    onResume = onResume
                )
            }
        }
    }
}

@Composable
private fun AgentLogsSection(
    message: ChatMessage,
    isLastMessage: Boolean
) {
    val logs = message.logs
    val outputLogs = remember(logs) { logs.filter { it.type == AgentLogType.OUTPUT } }
    val processLogs = remember(logs) {
        logs.filter { it.type == AgentLogType.THOUGHT || it.type == AgentLogType.TOOL_CALL }
    }
    val outputContent = remember(outputLogs, message.content) {
        outputLogs.lastOrNull { it.content.isNotBlank() && it.content != "{}" }?.content
            ?.takeIf { it.isNotBlank() }
            ?: message.content.takeIf { it.isNotBlank() }
    }
    val hasBodyOutput = outputContent != null
    val isWorkDone = hasBodyOutput

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)
    ) {
        if (hasBodyOutput) {
            MessageContent(
                content = outputContent.orEmpty(),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                isStreaming = message.isThinking && isLastMessage
            )

            if (processLogs.isNotEmpty() || message.reasoning.isNotBlank()) {
                ProcessTimelineCard(
                    logs = processLogs,
                    fallbackReasoning = message.reasoning,
                    isLastMessage = isLastMessage,
                    isThinking = message.isThinking && !isWorkDone
                )
            }
        } else if (logs.isEmpty()) {
            if (message.reasoning.isNotBlank()) {
                ThoughtCard(
                    logId = "reasoning-${message.id}",
                    content = message.reasoning,
                    isDone = !message.isThinking,
                    isInitialExpanded = message.isThinking,
                    isWorkDone = message.content.isNotBlank()
                )
            }
            if (message.content.isNotBlank()) {
                MessageContent(
                    content = message.content,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    isStreaming = message.isThinking
                )
            } else if (message.isThinking) {
                ThinkingIndicator()
            }
        } else {
            val lastThought = remember(processLogs) { processLogs.lastOrNull { it.type == AgentLogType.THOUGHT } }
            logs.forEachIndexed { index, logItem: AgentLogEntry ->
                key(logItem.id) {
                    AgentLogItem(
                        logItem = logItem,
                        message = message,
                        isLastMessage = isLastMessage,
                        isLastLog = index == logs.lastIndex,
                        lastThought = lastThought,
                        isWorkDone = isWorkDone
                    )
                }
            }

            val lastLog = logs.lastOrNull()
            if (lastLog == null || (lastLog.type == AgentLogType.OUTPUT && lastLog.content.isBlank())) {
                ThinkingIndicator()
            }
        }
    }
}

@Composable
private fun AgentLogItem(
    logItem: AgentLogEntry,
    message: ChatMessage,
    isLastMessage: Boolean,
    isLastLog: Boolean,
    lastThought: AgentLogEntry?,
    isWorkDone: Boolean
) {
    when (logItem.type) {
        AgentLogType.THOUGHT -> {
            val content = logItem.content
            if (content.isNotBlank() && content != "{}") {
                val isDone = !isLastLog || !message.isThinking
                ThoughtCard(
                    logId = logItem.id,
                    content = content,
                    isDone = isDone,
                    isInitialExpanded = isLastMessage && logItem == lastThought && !isDone,
                    isWorkDone = isWorkDone
                )
            }
        }

        AgentLogType.TOOL_CALL -> {
            val tName = logItem.toolName ?: "Unknown Tool"
            if (tName.isNotBlank() && tName != "{}") {
                ToolCallCard(
                    toolName = tName,
                    status = logItem.toolStatus ?: ToolCallStatus.RUNNING,
                    toolInput = logItem.toolInput,
                    toolOutput = logItem.toolOutput
                )
            }
        }

        AgentLogType.OUTPUT -> {
            val outContent = logItem.content
            if (outContent.isNotBlank() && outContent != "{}") {
                val isStreaming = isLastMessage && message.isThinking
                MessageContent(
                    content = outContent,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    isStreaming = isStreaming
                )
            }
        }
    }
}

/**
 * 中断后浮现的"继承上下文继续"卡片。
 *
 * - 遵循项目 UI motion 规范：使用减速曲线 + 短时长，不使用弹跳/回弹。
 * - 仅在 [AgentInterruption.resumable] 为 true 时展示按钮；否则只展示原因说明。
 */
@Composable
private fun ResumeContinueCard(
    interruption: AgentInterruption,
    onResume: (() -> Unit)?
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(interruption) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = expandFloatMotionSpec()) +
                expandVertically(animationSpec = expandMotionSpec()),
        exit = fadeOut(animationSpec = collapseFloatMotionSpec()) +
                shrinkVertically(animationSpec = collapseMotionSpec())
    ) {
        val labelColor = when (interruption.reason) {
            AgentInterruptionReason.REFUSAL -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.tertiary
        }
        Surface(
            shape = RoundedCornerShape(AgentTheme.Shapes.medium),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = labelColor.copy(alpha = 0.45f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AgentTheme.Spacing.xs)
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = AgentTheme.Spacing.md,
                    vertical = AgentTheme.Spacing.sm
                ),
                verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = labelColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = interruptionReasonLabel(interruption.reason),
                        style = MaterialTheme.typography.labelMedium,
                        color = labelColor,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = interruption.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (interruption.resumable && onResume != null) {
                    FilledTonalButton(
                        onClick = onResume,
                        modifier = Modifier.padding(top = AgentTheme.Spacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(AgentTheme.Spacing.xs))
                        Text(text = "继承上下文继续")
                    }
                }
            }
        }
    }
}

private fun interruptionReasonLabel(reason: AgentInterruptionReason): String = when (reason) {
    AgentInterruptionReason.IDLE_TIMEOUT -> "本轮分析已暂停（长时间无响应）"
    AgentInterruptionReason.MAX_TURN_REQUESTS -> "本轮分析已达上限"
    AgentInterruptionReason.USER_CANCELLED -> "本轮分析已被中断"
    AgentInterruptionReason.PROCESS_ERROR -> "Agent 进程异常"
    AgentInterruptionReason.REFUSAL -> "模型拒绝继续"
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isUser: Boolean
) {
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    BaseChatBubble(
        containerColor = containerColor,
        isUserMessage = isUser,
        cornerRadius = AgentTheme.Shapes.bubbleRadius,
        cornerRadiusSmall = AgentTheme.Shapes.bubbleRadiusSmall,
        contentPadding = AgentTheme.Spacing.bubblePadding
    ) {
        Column {
            // 正文内容
            if (message.content != "") {
                MessageContent(
                    content = message.content,
                    contentColor = contentColor
                )
            } else if (message.isThinking && !isUser && message.logs.isEmpty()) {
                // 仅在没有 logs 且在思考时显示动态指示器
                ThinkingIndicator()
            }
        }
    }
}

@Composable
private fun ProcessTimelineCard(
    logs: List<AgentLogEntry>,
    fallbackReasoning: String,
    isLastMessage: Boolean,
    isThinking: Boolean
) {
    var isExpanded by remember(logs, fallbackReasoning) { mutableStateOf(false) }
    val thoughtCount = remember(logs, fallbackReasoning) {
        logs.count { it.type == AgentLogType.THOUGHT } + if (logs.none { it.type == AgentLogType.THOUGHT } && fallbackReasoning.isNotBlank()) 1 else 0
    }
    val toolCount = remember(logs) { logs.count { it.type == AgentLogType.TOOL_CALL } }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = if (isExpanded) expandFloatMotionSpec() else collapseFloatMotionSpec(),
        label = "process_timeline_chevron"
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.large
            ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = if (isExpanded) expandMotionSpec() else collapseMotionSpec()
                )
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Psychology,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isThinking && isLastMessage) "思考与工具调用" else "已折叠的思考过程",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = buildString {
                            if (thoughtCount > 0) append("$thoughtCount 段思考")
                            if (thoughtCount > 0 && toolCount > 0) append(" · ")
                            if (toolCount > 0) append("$toolCount 次工具调用")
                            if (isThinking && isLastMessage) append(" · 持续更新")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    animationSpec = expandMotionSpec(),
                    expandFrom = Alignment.Top
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = EXPAND_MOTION_DURATION_MS - 40,
                        delayMillis = 20,
                        easing = DecelerateMotionEasing
                    )
                ),
                exit = shrinkVertically(
                    animationSpec = collapseMotionSpec(),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(
                    animationSpec = collapseFloatMotionSpec(
                        durationMillis = COLLAPSE_MOTION_DURATION_MS - 40
                    )
                )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)) {
                    if (logs.none { it.type == AgentLogType.THOUGHT } && fallbackReasoning.isNotBlank()) {
                        ThoughtCard(
                            logId = "reasoning-fallback",
                            content = fallbackReasoning,
                            isDone = !isThinking,
                            isInitialExpanded = false,
                            isWorkDone = true
                        )
                    }

                    logs.forEachIndexed { index, logItem ->
                        key(logItem.id) {
                            when (logItem.type) {
                                AgentLogType.THOUGHT -> {
                                    if (logItem.content.isNotBlank() && logItem.content != "{}") {
                                        ThoughtCard(
                                            logId = logItem.id,
                                            content = logItem.content,
                                            isDone = !isThinking || index != logs.lastIndex,
                                            isInitialExpanded = false,
                                            isWorkDone = true
                                        )
                                    }
                                }

                                AgentLogType.TOOL_CALL -> {
                                    val toolName = logItem.toolName ?: "Unknown Tool"
                                    if (toolName.isNotBlank() && toolName != "{}") {
                                        ToolCallCard(
                                            toolName = toolName,
                                            status = logItem.toolStatus ?: ToolCallStatus.RUNNING,
                                            toolInput = logItem.toolInput,
                                            toolOutput = logItem.toolOutput
                                        )
                                    }
                                }

                                AgentLogType.OUTPUT -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThoughtCard(
    logId: String,
    content: String,
    isDone: Boolean = false,
    isInitialExpanded: Boolean = false,
    isWorkDone: Boolean = false
) {
    // 计时器：记录从首次渲染到完成的秒数
    var elapsedSeconds by remember(logId) { mutableStateOf(0) }
    var isExpanded by remember(logId) { mutableStateOf(isInitialExpanded) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = if (isExpanded) expandFloatMotionSpec() else collapseFloatMotionSpec(),
        label = "thought_card_chevron"
    )

    // 使用 rememberUpdatedState 避免重启
    val currentIsDone by rememberUpdatedState(isDone)
    LaunchedEffect(Unit) {
        while (!currentIsDone) {
            kotlinx.coroutines.delay(1000)
            elapsedSeconds++
        }
    }

    // 完成时自动折叠
    LaunchedEffect(isDone) {
        if (isDone) {
            isExpanded = false
        }
    }

    // 工作完成维度：agent 开始输出正文时自动折叠所有思考内容
    LaunchedEffect(isWorkDone) {
        if (isWorkDone) {
            isExpanded = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = if (isExpanded) expandMotionSpec() else collapseMotionSpec()
            )
            .clickable { isExpanded = !isExpanded }
            .padding(vertical = AgentTheme.Spacing.xs)
    ) {
        // Header / Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
        ) {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .size(12.dp)
                    .rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )

            if (!isExpanded) {
                val labelText = if (isDone) {
                    "思考完成 (${elapsedSeconds}秒)"
                } else {
                    val preview = if (content.length > 50) "思考中..."
                                  else content.replace("\n", " ")
                    preview
                }
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(
                animationSpec = expandMotionSpec(),
                expandFrom = Alignment.Top
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = EXPAND_MOTION_DURATION_MS - 60,
                    delayMillis = 30,
                    easing = DecelerateMotionEasing
                )
            ),
            exit = shrinkVertically(
                animationSpec = collapseMotionSpec(),
                shrinkTowards = Alignment.Top
            ) + fadeOut(
                animationSpec = collapseFloatMotionSpec(
                    durationMillis = COLLAPSE_MOTION_DURATION_MS - 40
                )
            )
        ) {
            val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            val lineWidth = 1.5.dp
            val lineStart = 5.dp
            val lineRadius = 1.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AgentTheme.Spacing.xs)
                    .drawBehind {
                        drawRoundRect(
                            color = lineColor,
                            topLeft = Offset(lineStart.toPx(), 0f),
                            size = Size(lineWidth.toPx(), size.height),
                            cornerRadius = CornerRadius(lineRadius.toPx(), lineRadius.toPx())
                        )
                    }
                    .padding(start = 16.dp)
            ) {
                if (!isDone) {
                    Text(
                        text = content,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        style = TextStyle(
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    MarkdownText(
                        text = content,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        style = TextStyle(
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(
    toolName: String,
    status: ToolCallStatus,
    toolInput: String? = null,
    toolOutput: String? = null
) {
    val hasDetails = !toolInput.isNullOrBlank() || !toolOutput.isNullOrBlank()
    var isExpanded by remember(toolName, status, toolInput, toolOutput) {
        mutableStateOf(status == ToolCallStatus.RUNNING)
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = if (isExpanded) expandFloatMotionSpec() else collapseFloatMotionSpec(),
        label = "tool_call_chevron"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = if (isExpanded) expandMotionSpec() else collapseMotionSpec()
            )
            .padding(vertical = AgentTheme.Spacing.xs)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasDetails) Modifier.clickable { isExpanded = !isExpanded } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs)
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )

            when (status) {
                ToolCallStatus.RUNNING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
                ToolCallStatus.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Success",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
                    )
                }
                ToolCallStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = "Error",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    )
                }
            }

            Text(
                text = when (status) {
                    ToolCallStatus.RUNNING -> "正在使用 $toolName..."
                    ToolCallStatus.COMPLETED -> "已完成 $toolName"
                    ToolCallStatus.FAILED -> "使用 $toolName 失败"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            if (hasDetails) {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }

        AnimatedVisibility(
            visible = hasDetails && isExpanded,
            enter = expandVertically(
                animationSpec = expandMotionSpec(),
                expandFrom = Alignment.Top
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = EXPAND_MOTION_DURATION_MS - 60,
                    delayMillis = 30,
                    easing = DecelerateMotionEasing
                )
            ),
            exit = shrinkVertically(
                animationSpec = collapseMotionSpec(),
                shrinkTowards = Alignment.Top
            ) + fadeOut(
                animationSpec = collapseFloatMotionSpec(
                    durationMillis = COLLAPSE_MOTION_DURATION_MS - 40
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                toolInput
                    ?.takeIf { it.isNotBlank() && it != "{}" }
                    ?.let { detail ->
                        ToolCallDetailBlock(title = "输入", content = detail)
                    }
                toolOutput
                    ?.takeIf { it.isNotBlank() && it != "{}" }
                    ?.let { detail ->
                        ToolCallDetailBlock(title = "输出", content = detail)
                    }
            }
        }
    }
}

@Composable
private fun ToolCallDetailBlock(
    title: String,
    content: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = content,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = TextStyle(
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageContent(
    content: String,
    contentColor: Color,
    isStreaming: Boolean = false
) {
    if (isStreaming) {
        Text(
            text = content,
            color = contentColor,
            style = TextStyle(
                fontSize = 15.sp,
                lineHeight = 22.sp
            ),
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        AgentReportMarkdownText(
            text = content,
            color = contentColor,
            style = TextStyle(
                fontSize = 15.sp,
                lineHeight = 22.sp
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(3) { index ->
            val delay = index * 150
            val infiniteTransition = rememberInfiniteTransition(label = "thinking_$index")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )

            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            )
        }
    }
}
