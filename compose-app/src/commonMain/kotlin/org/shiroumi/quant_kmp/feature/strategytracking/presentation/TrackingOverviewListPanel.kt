package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import model.candle.StrategyPositionTrackingDay
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import org.shiroumi.quant_kmp.ui.theme.quantColors

/** 清仓记录最多展示的近端笔数。 */
private const val ClearedHistoryLimit = 8

/**
 * 持仓跟踪总览列表（左栏 / 移动端默认形态）。
 *
 * 展示最新交易日（或盘中实时日）的持有 / 选股 / 清仓三组列表。
 * 全部盈亏与价格由云端计算下发：持有行 = 成本→现价 + 浮动/最高收益 + 持有天数；
 * 选股行 = 模型分 + 现价与当日涨跌；清仓行 = 离场原因 + 规则口径已实现收益。
 *
 * @param bottomContentPadding 列表底部占位高度——移动端底部常驻全景图按钮悬浮于列表之上，
 *   通过该占位保证末行内容可完整滚出按钮覆盖区。
 */
@Composable
internal fun TrackingOverviewListPanel(
    timeline: StrategyPositionTrackingTimeline?,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onStockClick: (StrategyTrackingStockNode, StrategyTrackingSection, String) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    when {
        isLoading && timeline == null -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.md),
                ) {
                    CircularProgressIndicator(
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    Text(
                        text = "正在加载持仓列表",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        timeline == null || timeline.days.isEmpty() -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.md),
                ) {
                    Text(
                        text = error ?: "暂无策略持仓数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = onRefresh) {
                        Text("重新加载")
                    }
                }
            }
        }

        else -> {
            TrackingOverviewListContent(
                timeline = timeline,
                onStockClick = onStockClick,
                bottomContentPadding = bottomContentPadding,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun TrackingOverviewListContent(
    timeline: StrategyPositionTrackingTimeline,
    onStockClick: (StrategyTrackingStockNode, StrategyTrackingSection, String) -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val latestDay = timeline.days.last()
    val isRealtime = timeline.isRealtimeDay(latestDay)
    val dateLabel = if (isRealtime) "$TrackingRealtimeDayLabel · 盘中实时" else "${latestDay.tradeDate} · 最新交易日"
    val dayIndexByDate = timeline.days
        .mapIndexed { index, day -> day.tradeDate to index }
        .toMap()
    val lastDayIndex = timeline.days.lastIndex
    // 近端清仓记录：从最新日往回收集（含清仓发生日）
    val recentCleared = timeline.days.asReversed()
        .flatMap { day -> day.cleared.map { day.tradeDate to it } }
        .take(ClearedHistoryLimit)

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs),
        contentPadding = PaddingValues(bottom = bottomContentPadding),
    ) {
        item(key = "overview-date") {
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = AgentTheme.Spacing.xs),
            )
        }

        item(key = "overview-holdings-header") {
            TrackingListSectionHeader(
                title = "持有股票",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (latestDay.holdings.isEmpty()) {
            item(key = "overview-holdings-empty") { TrackingListEmptyRow("当前空仓") }
        } else {
            latestDay.holdings.forEach { node ->
                item(key = "overview-holding-${node.stockCode}") {
                    TrackingHoldingListRow(
                        node = node,
                        heldTradingDayOrdinal = node.buyDate
                            ?.let { dayIndexByDate[it] }
                            ?.let { lastDayIndex - it + 1 },
                        onClick = { onStockClick(node, StrategyTrackingSection.HOLDINGS, latestDay.tradeDate) },
                    )
                }
            }
        }

        item(key = "overview-selection-header") {
            TrackingListSectionHeader(
                title = "选股结果",
                tint = MaterialTheme.colorScheme.secondary,
                caption = "次日按波动率优先入场 1 只",
            )
        }
        if (latestDay.selection.isEmpty()) {
            item(key = "overview-selection-empty") { TrackingListEmptyRow("暂无选股") }
        } else {
            latestDay.selection.forEach { node ->
                item(key = "overview-selection-${node.stockCode}") {
                    TrackingSelectionListRow(
                        node = node,
                        onClick = { onStockClick(node, StrategyTrackingSection.SELECTION, latestDay.tradeDate) },
                    )
                }
            }
        }

        item(key = "overview-cleared-header") {
            TrackingListSectionHeader(
                title = "清仓记录",
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (recentCleared.isEmpty()) {
            item(key = "overview-cleared-empty") { TrackingListEmptyRow("窗口内暂无清仓") }
        } else {
            recentCleared.forEach { (tradeDate, node) ->
                item(key = "overview-cleared-$tradeDate-${node.stockCode}") {
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

@Composable
private fun TrackingListSectionHeader(
    title: String,
    tint: androidx.compose.ui.graphics.Color,
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
        modifier = Modifier.padding(horizontal = AgentTheme.Spacing.sm, vertical = AgentTheme.Spacing.xs),
    )
}

@Composable
private fun TrackingListRowContainer(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AgentTheme.Spacing.sm, vertical = AgentTheme.Spacing.sm),
    ) {
        content()
    }
}

/** 持有行：名称/代码 + 成本→现价 + 持有天数；右侧浮动收益与持有期最高收益。 */
@Composable
private fun TrackingHoldingListRow(
    node: StrategyTrackingStockNode,
    heldTradingDayOrdinal: Int?,
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TrackingListRowTitle(node = node)
                val costToCurrent = buildList {
                    node.buyPrice?.let { add("成本 ${formatListPrice(it)}") }
                    node.currentPrice?.let { add("现价 ${formatListPrice(it)}") }
                    heldTradingDayOrdinal?.let { add("持有第${it}日") }
                }.joinToString(" · ")
                if (costToCurrent.isNotEmpty()) {
                    Text(
                        text = costToCurrent,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                node.actualPnl?.let {
                    Text(
                        text = formatPnlPercent(it),
                        style = MaterialTheme.typography.titleSmall,
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TrackingListRowTitle(node = node)
                node.modelScore?.let {
                    Text(
                        text = "模型分 ${formatModelScore(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                node.currentPrice?.let {
                    Text(
                        text = formatListPrice(it),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                node.dayChangePct?.let {
                    Text(
                        text = formatPnlPercent(it),
                        style = MaterialTheme.typography.labelSmall,
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TrackingListRowTitle(node = node)
                Text(
                    text = "清仓 $clearedDate",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val realizedPnl = node.exitPnl ?: node.actualPnl
                realizedPnl?.let {
                    Text(
                        text = formatPnlPercent(it),
                        style = MaterialTheme.typography.titleSmall,
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
            style = MaterialTheme.typography.bodyMedium,
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
