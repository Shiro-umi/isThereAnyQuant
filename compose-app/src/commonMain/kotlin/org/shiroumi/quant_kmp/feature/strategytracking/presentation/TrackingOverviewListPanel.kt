package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import model.candle.StrategyTrackingNextExit
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import org.shiroumi.quant_kmp.ui.theme.quantColors

/** 清仓记录最多展示的近端笔数。 */
private const val ClearedHistoryLimit = 8

/**
 * 持仓跟踪总览列表（左栏 / 移动端默认形态）。
 *
 * 展示选定观察日（默认最新交易日 / 盘中实时日）的持有 / 选股 / 清仓三组列表，
 * 顶部日期导航条可在窗口内确认交易日之间翻页。
 * 全部盈亏与价格由云端计算下发：持有行 = 成本→现价 + 浮动/最高收益 + 持有天数 + 下一卖点；
 * 选股行 = 模型分 + 现价与当日涨跌；清仓行 = 离场原因 + 规则口径已实现收益。
 *
 * @param observedDate 当前浏览的观察日（yyyy-MM-dd）；null = 跟随最新日。
 * @param onObservedDateChange 翻页回调；传 null 回到最新日。
 * @param bottomContentPadding 列表底部占位高度——移动端底部常驻全景图按钮悬浮于列表之上，
 *   通过该占位保证末行内容可完整滚出按钮覆盖区。
 */
@Composable
internal fun TrackingOverviewListPanel(
    timeline: StrategyPositionTrackingTimeline,
    onStockClick: (StrategyTrackingStockNode, StrategyTrackingSection, String) -> Unit,
    observedDate: String?,
    onObservedDateChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    // 反馈态（加载中 / 无数据 / 加载失败）由 StrategyPositionTrackingScreen 在内容区统一短路渲染，
    // 此面板只在拿到有数据的 timeline 时被调用，直接渲染日期导航 + 三组列表。
    val days = timeline.days
    val latestIndex = days.lastIndex
    // 观察日落点：未指定或越界时回退最新日
    val observedIndex = observedDate
        ?.let { date -> days.indexOfLast { it.tradeDate == date }.takeIf { it >= 0 } }
        ?: latestIndex
    val observedDay = days[observedIndex]
    val isRealtime = timeline.isRealtimeDay(observedDay)
    val isLatest = observedIndex == latestIndex

    // 近端清仓记录：从观察日往回收集（含清仓发生日）
    val recentCleared = days.take(observedIndex + 1).asReversed()
        .flatMap { day -> day.cleared.map { day.tradeDate to it } }
        .take(ClearedHistoryLimit)

    Column(modifier = modifier) {
        TrackingDateNavigator(
            label = when {
                isRealtime -> "$TrackingRealtimeDayLabel · 盘中实时"
                isLatest -> "${observedDay.tradeDate} · 最新"
                else -> observedDay.tradeDate
            },
            canGoPrev = observedIndex > 0,
            canGoNext = observedIndex < latestIndex,
            isLatest = isLatest,
            onPrev = { onObservedDateChange(days[(observedIndex - 1).coerceAtLeast(0)].tradeDate) },
            onNext = {
                val nextIndex = (observedIndex + 1).coerceAtMost(latestIndex)
                onObservedDateChange(if (nextIndex == latestIndex) null else days[nextIndex].tradeDate)
            },
            onJumpLatest = { onObservedDateChange(null) },
        )

        AnimatedContent(
            targetState = observedDay.tradeDate,
            transitionSpec = {
                fadeIn(tween(AgentTheme.Durations.normal)) togetherWith
                    fadeOut(tween(AgentTheme.Durations.fast))
            },
            label = "tracking_list_day",
        ) { _ ->
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs),
                contentPadding = PaddingValues(
                    top = AgentTheme.Spacing.sm,
                    bottom = bottomContentPadding,
                ),
            ) {
                item(key = "holdings-header") {
                    TrackingListSectionHeader(
                        title = "持有股票",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (observedDay.holdings.isEmpty()) {
                    item(key = "holdings-empty") { TrackingListEmptyRow("当前空仓") }
                } else {
                    observedDay.holdings.forEach { node ->
                        item(key = "holding-${node.stockCode}") {
                            TrackingHoldingListRow(
                                node = node,
                                onClick = { onStockClick(node, StrategyTrackingSection.HOLDINGS, observedDay.tradeDate) },
                            )
                        }
                    }
                }

                item(key = "selection-header") {
                    TrackingListSectionHeader(
                        title = "选股结果",
                        tint = MaterialTheme.colorScheme.secondary,
                        caption = "次日开盘按评分优先买入，最多 3 只",
                    )
                }
                if (observedDay.selection.isEmpty()) {
                    item(key = "selection-empty") { TrackingListEmptyRow("暂无选股") }
                } else {
                    observedDay.selection.forEach { node ->
                        item(key = "selection-${node.stockCode}") {
                            TrackingSelectionListRow(
                                node = node,
                                onClick = { onStockClick(node, StrategyTrackingSection.SELECTION, observedDay.tradeDate) },
                            )
                        }
                    }
                }

                item(key = "cleared-header") {
                    TrackingListSectionHeader(
                        title = "清仓记录",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (recentCleared.isEmpty()) {
                    item(key = "cleared-empty") { TrackingListEmptyRow("窗口内暂无清仓") }
                } else {
                    recentCleared.forEach { (tradeDate, node) ->
                        item(key = "cleared-$tradeDate-${node.stockCode}") {
                            TrackingClearedListRow(
                                node = node,
                                clearedDate = tradeDate,
                                onClick = { onStockClick(node, StrategyTrackingSection.CLEARED, tradeDate) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 日期翻页导航条：‹ 日期 ›，非最新日时附「回到最新」。 */
@Composable
private fun TrackingDateNavigator(
    label: String,
    canGoPrev: Boolean,
    canGoNext: Boolean,
    isLatest: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJumpLatest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AgentTheme.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm),
    ) {
        NavArrow(
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            contentDescription = "前一交易日",
            enabled = canGoPrev,
            onClick = onPrev,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!isLatest) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.clip(RoundedCornerShape(percent = 50)).clickable(onClick = onJumpLatest),
            ) {
                Text(
                    text = "回到最新",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(
                        horizontal = AgentTheme.Spacing.md,
                        vertical = AgentTheme.Spacing.xs,
                    ),
                )
            }
        }
        NavArrow(
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "后一交易日",
            enabled = canGoNext,
            onClick = onNext,
        )
    }
}

@Composable
private fun NavArrow(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // 翻页箭头、回到最新、跟随校准三个交互控件统一走 secondaryContainer 次级强调族，
    // 深浅一致；禁用态对容器与图标同步降 alpha，弱化但不改变所属色族。
    val container = if (enabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
    }
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(container)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TrackingListSectionHeader(
    title: String,
    tint: Color,
    caption: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AgentTheme.Spacing.md, bottom = AgentTheme.Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = RoundedCornerShape(percent = 50),
            color = tint,
        ) {}
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
        caption?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrackingListEmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = AgentTheme.Spacing.sm, vertical = AgentTheme.Spacing.sm),
    )
}

@Composable
private fun TrackingListRowContainer(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = AgentTheme.Spacing.md,
                vertical = AgentTheme.Spacing.md,
            ),
        ) {
            content()
        }
    }
}

/** 持有行：名称/代码 + 成本→现价 + 持有天数；右侧浮动收益与最高收益；底部下一卖点提示。 */
@Composable
private fun TrackingHoldingListRow(
    node: StrategyTrackingStockNode,
    onClick: () -> Unit,
) {
    val pnlRise = MaterialTheme.colorScheme.primary
    val pnlFall = MaterialTheme.colorScheme.tertiary
    TrackingListRowContainer(onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    TrackingListRowTitle(node = node)
                    val costToCurrent = buildList {
                        node.buyPrice?.let { add("成本 ${formatListPrice(it)}") }
                        node.currentPrice?.let { add("现价 ${formatListPrice(it)}") }
                    }.joinToString(" · ")
                    if (costToCurrent.isNotEmpty()) {
                        Text(
                            text = costToCurrent,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    node.actualPnl?.let {
                        Text(
                            text = formatPnlPercent(it),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (it >= 0) pnlRise else pnlFall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    node.maxPnl?.let {
                        Text(
                            text = "最高 ${formatPnlPercent(it)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = (if (it >= 0) pnlRise else pnlFall).copy(alpha = 0.65f),
                        )
                    }
                }
            }
            node.nextExit?.let { NextExitStrip(it) }
        }
    }
}

/** 下一卖点提示条：止盈价 / 保盈价 / 到期日，弱化容器内呈现。 */
@Composable
private fun NextExitStrip(nextExit: StrategyTrackingNextExit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AgentTheme.Spacing.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NextExitItem(
                icon = Icons.Outlined.Bolt,
                label = "止盈",
                value = formatListPrice(nextExit.takeProfitPrice),
                tint = MaterialTheme.colorScheme.primary,
            )
            nextExit.profitProtectPrice?.let {
                NextExitItem(
                    icon = Icons.Outlined.Shield,
                    label = "保盈",
                    value = formatListPrice(it),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            nextExit.timeStopDate?.let { date ->
                val remaining = nextExit.timeStopInTradingDays
                NextExitItem(
                    icon = Icons.Outlined.Schedule,
                    label = "到期",
                    value = if (remaining != null && remaining <= 0) "明日" else date.takeLast(5),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NextExitItem(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 选股行：名称/代码 + 模型分；右侧现价与当日实时涨跌（实时行情方向用红涨绿跌语义色）。 */
@Composable
private fun TrackingSelectionListRow(
    node: StrategyTrackingStockNode,
    onClick: () -> Unit,
) {
    TrackingListRowContainer(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                TrackingListRowTitle(node = node)
                node.modelScore?.let {
                    Text(
                        text = "模型分 ${formatModelScore(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                node.currentPrice?.let {
                    Text(
                        text = formatListPrice(it),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                node.dayChangePct?.let {
                    Text(
                        text = formatPnlPercent(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            it > 0f -> MaterialTheme.quantColors.bullish
                            it < 0f -> MaterialTheme.quantColors.bearish
                            else -> MaterialTheme.quantColors.neutral
                        },
                    )
                }
            }
        }
    }
}

/** 清仓行：名称/代码 + 清仓日期；右侧规则口径已实现收益 + 离场原因。 */
@Composable
private fun TrackingClearedListRow(
    node: StrategyTrackingStockNode,
    clearedDate: String,
    onClick: () -> Unit,
) {
    val pnlRise = MaterialTheme.colorScheme.primary
    val pnlFall = MaterialTheme.colorScheme.tertiary
    TrackingListRowContainer(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                TrackingListRowTitle(node = node)
                Text(
                    text = "清仓 $clearedDate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val realizedPnl = node.exitPnl ?: node.actualPnl
                realizedPnl?.let {
                    Text(
                        text = formatPnlPercent(it),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (it >= 0) pnlRise else pnlFall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                node.exitReason?.let {
                    Text(
                        text = it.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = (realizedPnl?.let { pnl -> if (pnl >= 0) pnlRise else pnlFall } ?: pnlRise)
                            .copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackingListRowTitle(node: StrategyTrackingStockNode) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = node.stockName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = node.stockCode,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            maxLines = 1,
        )
    }
}

private fun formatListPrice(value: Float): String {
    val scaled = kotlin.math.round(value * 100).toInt()
    val intPart = scaled / 100
    val frac = kotlin.math.abs(scaled % 100)
    return "$intPart.${frac.toString().padStart(2, '0')}"
}

private fun formatModelScore(value: Double): String {
    val scaled = kotlin.math.round(value * 10000).toInt()
    val intPart = scaled / 10000
    val frac = kotlin.math.abs(scaled % 10000)
    return "$intPart.${frac.toString().padStart(4, '0')}"
}
