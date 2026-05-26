package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import model.candle.StrategyPositionTrackingDay
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode
import org.shiroumi.quant_kmp.di.HttpClientProvider
import org.shiroumi.quant_kmp.feature.candle.data.repository.CandleRepositoryImpl
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.navigation.ProvideMobileTitleBar

private val CompactPagePadding = 16.dp
private val DefaultPagePadding = 24.dp
private val HoldContinueStroke = 3.0f
private val ExitClearStroke = 2.25f
private val EnterHoldingStroke = 1.75f
private const val HeaderEnterDurationMs = 250
private const val TimelineEnterDurationMs = 300
private const val EdgeEnterDurationMs = 250
private const val CardEnterDurationMs = 300
private const val CardEnterOverlapMs = 30L
private const val EdgeStepOverlapMs = 30L
private const val DetailContainerTransitionMs = 380
private const val DetailContentEnterDelayMs = 160L
private const val DetailOverlayExitMs = 220L

private class TrackingTimelineOverlayState {
    private val hiddenCardFlags = mutableStateMapOf<String, Boolean>()
    private var currentHiddenCardKey: String? = null

    fun setHiddenCardKey(cardKey: String?) {
        val previous = currentHiddenCardKey
        if (previous == cardKey) return
        previous?.let { hiddenCardFlags[it] = false }
        currentHiddenCardKey = cardKey
        cardKey?.let { hiddenCardFlags[it] = true }
    }

    fun isHidden(cardKey: String): Boolean = hiddenCardFlags[cardKey] == true
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun StrategyPositionTrackingScreen(
    viewModel: StrategyPositionTrackingViewModel = viewModel {
        val repository = CandleRepositoryImpl(
            httpClient = HttpClientProvider.apiClient,
            baseUrl = org.shiroumi.config.AppConfig.apiBaseUrl
        )
        StrategyPositionTrackingViewModel(repository)
    }
) {
    val timeline by viewModel.timeline.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedStock by viewModel.selectedStock.collectAsState()
    val selectedDetail by viewModel.selectedDetail.collectAsState()
    val config = rememberAdaptiveLayoutConfig()
    val pagePadding = when {
        config.isExpanded || config.isLarge || config.isXLarge -> PaddingValues(40.dp)
        config.isMedium -> PaddingValues(24.dp)
        else -> PaddingValues(horizontal = 16.dp, vertical = 0.dp)
    }
    var disclaimerVisible by remember { mutableStateOf(false) }
    // Compact / Medium 由 TopBar 承载标题；Expanded 及以上由页内 TrackingPageHeader 大字承载
    if (config.isCompact || config.isMedium) {
        ProvideMobileTitleBar(
            title = "策略持仓跟踪",
            actions = {
                DisclaimerIcon(
                    expanded = disclaimerVisible,
                    onClick = { disclaimerVisible = true },
                )
            },
        )
    }
    var overlayAnchor by remember {
        mutableStateOf<TrackingOverlayAnchorState?>(null)
    }
    val timelineOverlayState = remember { TrackingTimelineOverlayState() }
    var detailContentVisible by remember { mutableStateOf(false) }
    val detailVisible = selectedStock != null

    LaunchedEffect(selectedStock?.cardKey) {
        val stock = selectedStock ?: return@LaunchedEffect
        overlayAnchor = stock.toOverlayAnchorState()
    }

    LaunchedEffect(detailVisible, overlayAnchor?.cardKey) {
        if (detailVisible && overlayAnchor != null) {
            detailContentVisible = false
            delay(DetailContentEnterDelayMs)
            if (selectedStock?.cardKey == overlayAnchor?.cardKey) {
                detailContentVisible = true
            }
        } else {
            detailContentVisible = false
            delay(DetailOverlayExitMs)
            if (selectedStock == null) {
                overlayAnchor = null
            }
        }
    }

    LaunchedEffect(selectedStock?.cardKey, overlayAnchor?.cardKey) {
        timelineOverlayState.setHiddenCardKey(
            if (selectedStock != null && overlayAnchor != null) {
                overlayAnchor?.cardKey
            } else {
                null
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pagePadding)
            ) {
                SharedTransitionLayout {
                    AnimatedVisibility(
                        visible = true,
                        enter = EnterTransition.None,
                        exit = ExitTransition.None,
                        modifier = Modifier.fillMaxSize(),
                        label = "tracking_shared_scope"
                    ) {
                        val timelineAnimatedScope = this
                        Box(modifier = Modifier.fillMaxSize()) {
                            TrackingTimelineScaffold(
                                timeline = timeline,
                                isLoading = isLoading,
                                error = error,
                                onRefresh = viewModel::refresh,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = timelineAnimatedScope,
                                overlayState = timelineOverlayState,
                                showInlineHeader = !config.isCompact && !config.isMedium,
                                disclaimerVisible = disclaimerVisible,
                                onDisclaimerToggle = { disclaimerVisible = !disclaimerVisible },
                                onStockClick = { node, section, tradeDate ->
                                    viewModel.selectStock(node, section, tradeDate)
                                },
                                modifier = Modifier.fillMaxSize(),
                            )

                            AnimatedVisibility(
                                visible = detailVisible && overlayAnchor != null,
                                enter = fadeIn(
                                    animationSpec = tween(220, easing = LinearOutSlowInEasing)
                                ),
                                exit = fadeOut(
                                    animationSpec = tween(DetailOverlayExitMs.toInt(), easing = LinearOutSlowInEasing)
                                ),
                                modifier = Modifier.fillMaxSize(),
                                label = "tracking_detail_overlay"
                            ) {
                                overlayAnchor?.let { anchor ->
                                    StockDetailCard(
                                        anchor = anchor,
                                        detail = selectedDetail?.takeIf { it.selected.cardKey == anchor.cardKey },
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        sharedAnimatedVisibilityScope = this,
                                        contentVisible = detailContentVisible,
                                        onDismiss = viewModel::dismissDetail,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (disclaimerVisible) {
                val useBottomSheet = config.isCompact || config.isMedium
                if (useBottomSheet) {
                    DisclaimerBottomSheet(onDismiss = { disclaimerVisible = false })
                } else {
                    DisclaimerDialog(onDismiss = { disclaimerVisible = false })
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TrackingTimelineScaffold(
    timeline: StrategyPositionTrackingTimeline?,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    overlayState: TrackingTimelineOverlayState,
    showInlineHeader: Boolean,
    disclaimerVisible: Boolean,
    onDisclaimerToggle: () -> Unit,
    onStockClick: (StrategyTrackingStockNode, StrategyTrackingSection, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading && timeline == null -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                TrackingLoadingState()
            }
        }

        timeline == null || timeline.days.isEmpty() -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                TrackingFeedbackState(
                    title = if (error.isNullOrBlank()) "暂无策略持仓跟踪数据" else "策略持仓跟踪加载失败",
                    message = error ?: "策略审计数据生成后，这里会展示最近 20 个交易日的持仓变化树。",
                    actionLabel = "重新加载",
                    onAction = onRefresh,
                )
            }
        }

        else -> {
            val config = rememberAdaptiveLayoutConfig()
            val layoutSpec = remember(config) { trackingTimelineLayoutSpec(config) }
            var hasAutoScrolled by remember { mutableStateOf(false) }
            var pageVisible by remember { mutableStateOf(false) }
            var shouldAnimateTimelineSequence by remember { mutableStateOf(true) }
            var revealedDayCount by remember { mutableStateOf(0) }
            var revealedConnectorCount by remember { mutableStateOf(0) }
            var revealStartDayIndex by remember { mutableStateOf(0) }
            var revealStartConnectorIndex by remember { mutableStateOf(0) }
            val headerAlpha by animateFloatAsState(
                targetValue = if (pageVisible) 1f else 0f,
                animationSpec = tween(durationMillis = HeaderEnterDurationMs, easing = LinearOutSlowInEasing),
                label = "tracking_header_alpha"
            )
            val timelineAlpha by animateFloatAsState(
                targetValue = if (pageVisible) 1f else 0f,
                animationSpec = tween(durationMillis = TimelineEnterDurationMs, delayMillis = 60, easing = LinearOutSlowInEasing),
                label = "tracking_timeline_alpha"
            )
            val headerOffset by animateIntOffsetAsState(
                targetValue = if (pageVisible) IntOffset.Zero else IntOffset(0, 16),
                animationSpec = tween(durationMillis = HeaderEnterDurationMs, easing = LinearOutSlowInEasing),
                label = "tracking_header_offset"
            )
            val timelineOffset by animateIntOffsetAsState(
                targetValue = if (pageVisible) IntOffset.Zero else IntOffset(0, 20),
                animationSpec = tween(durationMillis = TimelineEnterDurationMs, delayMillis = 60, easing = LinearOutSlowInEasing),
                label = "tracking_timeline_offset"
            )

            LaunchedEffect(Unit) {
                pageVisible = true
            }

            LaunchedEffect(timeline.days.firstOrNull()?.tradeDate) {
                if (timeline.days.isEmpty()) return@LaunchedEffect
                if (!shouldAnimateTimelineSequence) {
                    revealedDayCount = timeline.days.size
                    revealedConnectorCount = (timeline.days.size - 1).coerceAtLeast(0)
                    revealStartDayIndex = 0
                    revealStartConnectorIndex = 0
                    return@LaunchedEffect
                }
            }

            LaunchedEffect(timeline.days.lastOrNull()?.tradeDate) {
                if (!hasAutoScrolled && timeline.days.isNotEmpty()) {
                    hasAutoScrolled = true
                }
            }

            val titleContentGap = when {
                config.isExpanded || config.isLarge || config.isXLarge -> 24.dp
                config.isMedium -> 18.dp
                else -> 14.dp
            }

            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(titleContentGap)
            ) {
                if (showInlineHeader) {
                    Box(
                        modifier = Modifier
                            .offset { headerOffset }
                            .graphicsLayer(alpha = headerAlpha)
                    ) {
                        TrackingPageHeader(
                            disclaimerExpanded = disclaimerVisible,
                            onDisclaimerToggle = onDisclaimerToggle,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .offset { timelineOffset }
                        .graphicsLayer(alpha = timelineAlpha)
                ) {
                    TrackingTimelineContent(
                        timeline = timeline,
                        layoutSpec = layoutSpec,
                        shouldAutoScrollToLatest = hasAutoScrolled,
                        animateTimelineSequence = shouldAnimateTimelineSequence,
                        revealedDayCount = revealedDayCount,
                        revealedConnectorCount = revealedConnectorCount,
                        onRevealSequencePrepared = { startDayIndex, startConnectorIndex ->
                            if (!shouldAnimateTimelineSequence) return@TrackingTimelineContent
                            revealStartDayIndex = startDayIndex
                            revealStartConnectorIndex = startConnectorIndex
                            revealedDayCount = startDayIndex
                            revealedConnectorCount = startConnectorIndex
                        },
                        onRevealDay = { nextDayCount ->
                            revealedDayCount = nextDayCount
                        },
                        onRevealConnector = { nextConnectorCount ->
                            revealedConnectorCount = nextConnectorCount
                        },
                        revealStartDayIndex = revealStartDayIndex,
                        revealStartConnectorIndex = revealStartConnectorIndex,
                        onRevealSequenceFinished = {
                            shouldAnimateTimelineSequence = false
                            revealStartDayIndex = 0
                            revealStartConnectorIndex = 0
                        },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        overlayState = overlayState,
                        onStockClick = onStockClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackingPageHeader(
    disclaimerExpanded: Boolean,
    onDisclaimerToggle: () -> Unit,
) {
    val config = rememberAdaptiveLayoutConfig()
    val titleStyle = when {
        config.isExpanded || config.isLarge || config.isXLarge -> MaterialTheme.typography.displaySmall
        config.isMedium -> MaterialTheme.typography.headlineLarge
        else -> MaterialTheme.typography.headlineMedium
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "策略持仓跟踪",
            style = titleStyle,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        DisclaimerIcon(
            expanded = disclaimerExpanded,
            onClick = onDisclaimerToggle,
        )
    }
}

@Composable
private fun DisclaimerIcon(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue = if (expanded)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(200),
    )
    val textColor by animateColorAsState(
        targetValue = if (expanded)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
    )
    Box(
        modifier = Modifier
            .size(22.dp)
            .border(1.5.dp, borderColor, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "!",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
    }
}

private val DisclaimerItems = listOf(
    "策略选股结果仅供跟踪参考，不构成投资建议",
    "次日大幅低开时，建议观望而非追入",
    "入选当日最高涨幅已大幅偏离成本区时，追高风险较大",
    "潜在追高空间接近或超过 50% 时，建议谨慎买入",
)

@Composable
private fun DisclaimerList(
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DisclaimerItems.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "·",
                    style = textStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = item,
                    style = textStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisclaimerBottomSheet(
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val dismissSheet: () -> Unit = {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "跟踪说明",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = dismissSheet) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 0.5.dp,
        )

        DisclaimerList(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun DisclaimerDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "跟踪说明",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            DisclaimerList(textStyle = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TrackingTimelineContent(
    timeline: StrategyPositionTrackingTimeline,
    layoutSpec: TimelineLayoutSpec,
    shouldAutoScrollToLatest: Boolean,
    animateTimelineSequence: Boolean,
    revealedDayCount: Int,
    revealedConnectorCount: Int,
    revealStartDayIndex: Int,
    revealStartConnectorIndex: Int,
    onRevealSequencePrepared: (startDayIndex: Int, startConnectorIndex: Int) -> Unit,
    onRevealDay: (Int) -> Unit,
    onRevealConnector: (Int) -> Unit,
    onRevealSequenceFinished: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    overlayState: TrackingTimelineOverlayState,
    onStockClick: (StrategyTrackingStockNode, StrategyTrackingSection, String) -> Unit,
) {
    val listState = rememberLazyListState()
    val verticalScrollState = rememberScrollState()
    val isScrolling by remember { derivedStateOf { listState.isScrollInProgress || verticalScrollState.isScrollInProgress } }
    val computeRuntime = remember { platformComputeRuntime() }
    val edgeAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = EdgeEnterDurationMs, easing = LinearOutSlowInEasing),
        label = "tracking_edge_alpha"
    )
    val dayRevealProgress by animateFloatAsState(
        targetValue = if (animateTimelineSequence) {
            revealedDayCount.toFloat()
        } else {
            timeline.days.size.toFloat()
        },
        animationSpec = tween(durationMillis = CardEnterDurationMs, easing = LinearOutSlowInEasing),
        label = "tracking_day_reveal_progress"
    )
    val connectorRevealProgress by animateFloatAsState(
        targetValue = if (animateTimelineSequence) {
            revealedConnectorCount.toFloat()
        } else {
            (timeline.days.size - 1).coerceAtLeast(0).toFloat()
        },
        animationSpec = tween(durationMillis = EdgeEnterDurationMs, easing = LinearOutSlowInEasing),
        label = "tracking_connector_reveal_progress"
    )
    val timelineDescription = remember(timeline) { timeline.accessibilityDescription() }
    val colorScheme = MaterialTheme.colorScheme
    val edgePalette = remember(colorScheme.primary, colorScheme.tertiary, colorScheme.secondary) {
        TrackingEdgePalette(
            holdContinue = colorScheme.primary,
            exitClear = colorScheme.tertiary,
            enterHolding = colorScheme.secondary,
        )
    }
    val edgeLayoutInput = remember(timeline) { timeline.toTrackingEdgeLayoutInput() }
    var edgeLayout by remember { mutableStateOf(TrackingEdgeLayoutResult()) }
    var edgeLayoutRequestVersion by remember { mutableStateOf(0L) }
    LaunchedEffect(edgeLayoutInput, computeRuntime) {
        val requestVersion = edgeLayoutRequestVersion + 1
        edgeLayoutRequestVersion = requestVersion
        val nextLayout = computeRuntime.buildTrackingEdgeLayout(edgeLayoutInput)
        if (edgeLayoutRequestVersion == requestVersion) {
            edgeLayout = nextLayout
        }
    }

    LaunchedEffect(shouldAutoScrollToLatest) {
        if (shouldAutoScrollToLatest && timeline.days.isNotEmpty() && !animateTimelineSequence) {
            listState.scrollToItem(timeline.days.lastIndex)
        }
    }

    LaunchedEffect(animateTimelineSequence, timeline.days.map { it.tradeDate }) {
        if (!animateTimelineSequence || timeline.days.isEmpty()) {
            return@LaunchedEffect
        }

        listState.scrollToItem(timeline.days.lastIndex)

        val visibleRange = snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .first { it.isNotEmpty() }
        val startDayIndex = visibleRange.minOrNull()?.coerceIn(0, timeline.days.lastIndex) ?: 0
        val startConnectorIndex = startDayIndex.coerceAtMost((timeline.days.size - 1).coerceAtLeast(0))
        onRevealSequencePrepared(startDayIndex, startConnectorIndex)

        (startDayIndex..timeline.days.lastIndex).forEach { index ->
            onRevealDay(index + 1)
            if (index < timeline.days.lastIndex) {
                delay(CardEnterOverlapMs)
                onRevealConnector(index + 1)
                delay(EdgeStepOverlapMs)
            }
        }
        delay(CardEnterDurationMs.toLong())
        onRevealSequenceFinished()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(clip = true)
            .semantics { contentDescription = timelineDescription }
    ) {
        val geometry = remember(layoutSpec, maxWidth, maxHeight) {
            timelineGeometry(
                spec = layoutSpec,
                viewportWidth = maxWidth,
                viewportHeight = maxHeight,
            )
        }
        val totalContentHeight = geometry.lazyRowTop + geometry.cardHeight + geometry.lazyRowBottom
        val requiresVerticalScroll = totalContentHeight > maxHeight
        val containerHeight = if (requiresVerticalScroll) totalContentHeight else maxHeight

        if (requiresVerticalScroll) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(containerHeight)
                ) {
                    LazyRow(
                        state = listState,
                        horizontalArrangement = Arrangement.spacedBy(geometry.columnGap),
                        contentPadding = PaddingValues(
                            start = geometry.edgeContentPadding,
                            end = geometry.edgeContentPadding,
                            top = geometry.lazyRowTop,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(containerHeight)
                    ) {
                        itemsIndexed(
                            items = timeline.days,
                            key = { _, day -> day.tradeDate }
                        ) { index, day ->
                            TrackingDayCard(
                                day = day,
                                dayIndex = index,
                                dayRevealProgress = dayRevealProgress,
                                revealStartDayIndex = revealStartDayIndex,
                                isLatest = index == timeline.days.lastIndex,
                                isRealtime = timeline.isRealtimeDay(day),
                                geometry = geometry,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                overlayState = overlayState,
                                isScrolling = isScrolling,
                                onStockClick = onStockClick,
                            )
                        }
                    }

                    TrackingTimelineEdgesCanvas(
                        listState = listState,
                        geometry = geometry,
                        indexedEdges = edgeLayout.indexedEdges,
                        connectorRevealProgress = connectorRevealProgress,
                        revealStartConnectorIndex = revealStartConnectorIndex,
                        edgeAlpha = edgeAlpha,
                        edgePalette = edgePalette,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(geometry.columnGap),
                    contentPadding = PaddingValues(
                        start = geometry.edgeContentPadding,
                        end = geometry.edgeContentPadding,
                        top = geometry.lazyRowTop,
                        bottom = geometry.lazyRowBottom,
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = timeline.days,
                        key = { _, day -> day.tradeDate }
                    ) { index, day ->
                        TrackingDayCard(
                            day = day,
                            dayIndex = index,
                            dayRevealProgress = dayRevealProgress,
                            revealStartDayIndex = revealStartDayIndex,
                            isLatest = index == timeline.days.lastIndex,
                            isRealtime = timeline.isRealtimeDay(day),
                            geometry = geometry,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            overlayState = overlayState,
                            isScrolling = isScrolling,
                            onStockClick = onStockClick,
                        )
                    }
                }

                TrackingTimelineEdgesCanvas(
                    listState = listState,
                    geometry = geometry,
                    indexedEdges = edgeLayout.indexedEdges,
                    connectorRevealProgress = connectorRevealProgress,
                    revealStartConnectorIndex = revealStartConnectorIndex,
                    edgeAlpha = edgeAlpha,
                    edgePalette = edgePalette,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        TimelineEdgeFade(
            modifier = Modifier.align(Alignment.CenterStart),
            width = geometry.sideFadeWidth,
        )
        TimelineEdgeFade(
            modifier = Modifier.align(Alignment.CenterEnd),
            reversed = true,
            width = geometry.sideFadeWidth,
        )
    }
}

@Composable
private fun TrackingTimelineEdgesCanvas(
    listState: androidx.compose.foundation.lazy.LazyListState,
    geometry: TimelineGeometry,
    indexedEdges: List<IndexedTrackingEdgePayload>,
    connectorRevealProgress: Float,
    revealStartConnectorIndex: Int,
    edgeAlpha: Float,
    edgePalette: TrackingEdgePalette,
    modifier: Modifier = Modifier,
) {
    val reusablePath = remember { Path() }
    val holdContinueStroke = remember {
        Stroke(width = HoldContinueStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val exitClearStroke = remember {
        Stroke(width = ExitClearStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val enterHoldingStroke = remember {
        Stroke(width = EnterHoldingStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    val edgesByFromIndex = remember(indexedEdges) {
        indexedEdges.groupBy { it.fromIndex }
    }

    val holdContinueBase = remember(edgePalette) {
        edgePalette.holdContinue.copy(alpha = 0.92f)
    }
    val exitClearBase = remember(edgePalette) {
        edgePalette.exitClear.copy(alpha = 0.82f)
    }
    val enterHoldingBase = remember(edgePalette) {
        edgePalette.enterHolding.copy(alpha = 0.72f)
    }

    Canvas(modifier = modifier) {
        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty() || indexedEdges.isEmpty()) return@Canvas

        val firstVisibleItem = visibleItems.first()
        val lastVisibleItem = visibleItems.last()
        val visibleStart = firstVisibleItem.index - 1
        val visibleEnd = lastVisibleItem.index + 1
        val contentStartPx = layoutInfo.beforeContentPadding.toFloat()
        val itemStride = geometry.columnWidth.toPx() + geometry.columnGap.toPx()
        val firstVisibleOffsetPx = contentStartPx + firstVisibleItem.offset.toFloat()
        val outboundAnchorXPx = geometry.outboundAnchorX.toPx()
        val inboundAnchorXPx = geometry.inboundAnchorX.toPx()
        val selectionSlotCenters = FloatArray(TrackingSlotCount) { slotIndex ->
            geometry.slotCenterY(StrategyTrackingSection.SELECTION, slotIndex).toPx()
        }
        val holdingSlotCenters = FloatArray(TrackingSlotCount) { slotIndex ->
            geometry.slotCenterY(StrategyTrackingSection.HOLDINGS, slotIndex).toPx()
        }
        val clearedSlotCenters = FloatArray(TrackingSlotCount) { slotIndex ->
            geometry.slotCenterY(StrategyTrackingSection.CLEARED, slotIndex).toPx()
        }
        fun slotCenter(section: StrategyTrackingSection, slotIndex: Int): Float = when (section) {
            StrategyTrackingSection.SELECTION -> selectionSlotCenters[slotIndex]
            StrategyTrackingSection.HOLDINGS -> holdingSlotCenters[slotIndex]
            StrategyTrackingSection.CLEARED -> clearedSlotCenters[slotIndex]
        }

        fun columnOffset(index: Int): Float =
            firstVisibleOffsetPx + (index - firstVisibleItem.index) * itemStride

        val fromIndexStart = (visibleStart - 1).coerceAtLeast(0)
        for (fromIndex in fromIndexStart..visibleEnd) {
            val edges = edgesByFromIndex[fromIndex]
            if (edges.isNullOrEmpty()) continue
            for (indexedEdge in edges) {
                val connectorIndex = indexedEdge.fromIndex
                val connectorAlpha = when {
                    connectorIndex < revealStartConnectorIndex -> edgeAlpha
                    else -> (connectorRevealProgress - connectorIndex).coerceIn(0f, 1f) * edgeAlpha
                }
                if (connectorAlpha <= 0f) continue
                if (indexedEdge.toIndex !in visibleStart..visibleEnd) continue

                val fromX = columnOffset(indexedEdge.fromIndex)
                val toX = columnOffset(indexedEdge.toIndex)

                val start = Offset(
                    x = fromX + outboundAnchorXPx,
                    y = slotCenter(indexedEdge.fromSection, indexedEdge.fromSlotIndex)
                )
                val end = Offset(
                    x = toX + inboundAnchorXPx,
                    y = slotCenter(indexedEdge.toSection, indexedEdge.toSlotIndex)
                )

                val controlDx = (end.x - start.x) * 0.36f
                reusablePath.reset()
                reusablePath.moveTo(start.x, start.y)
                reusablePath.cubicTo(
                    start.x + controlDx,
                    start.y,
                    end.x - controlDx,
                    end.y,
                    end.x,
                    end.y
                )

                val baseColor = when (indexedEdge.kind) {
                    TrackingEdgeKind.HOLD_CONTINUE -> holdContinueBase
                    TrackingEdgeKind.EXIT_CLEAR -> exitClearBase
                    TrackingEdgeKind.ENTER_HOLDING -> enterHoldingBase
                }
                val color = baseColor.copy(alpha = baseColor.alpha * connectorAlpha)
                val stroke = when (indexedEdge.kind) {
                    TrackingEdgeKind.HOLD_CONTINUE -> holdContinueStroke
                    TrackingEdgeKind.EXIT_CLEAR -> exitClearStroke
                    TrackingEdgeKind.ENTER_HOLDING -> enterHoldingStroke
                }
                drawPath(
                    path = reusablePath,
                    color = color,
                    style = stroke,
                )
            }
        }
    }
}

@Composable
private fun TimelineEdgeFade(
    modifier: Modifier = Modifier,
    reversed: Boolean = false,
    width: Dp,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val transparent = surfaceColor.copy(alpha = 0f)
    val mid = surfaceColor.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(
                brush = Brush.horizontalGradient(
                    colorStops = if (reversed) {
                        arrayOf(
                            0f to transparent,
                            0.5f to mid,
                            1f to surfaceColor,
                        )
                    } else {
                        arrayOf(
                            0f to surfaceColor,
                            0.5f to mid,
                            1f to transparent,
                        )
                    }
                )
            )
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TrackingDayCard(
    day: StrategyPositionTrackingDay,
    dayIndex: Int,
    dayRevealProgress: Float,
    revealStartDayIndex: Int,
    isLatest: Boolean,
    isRealtime: Boolean,
    geometry: TimelineGeometry,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    overlayState: TrackingTimelineOverlayState,
    isScrolling: Boolean,
    modifier: Modifier = Modifier,
    onStockClick: (StrategyTrackingStockNode, StrategyTrackingSection, String) -> Unit = { _, _, _ -> },
) {
    val isRevealed = dayIndex < revealStartDayIndex || dayRevealProgress >= dayIndex + 1f
    val revealModifier = if (isRevealed) {
        Modifier
    } else {
        val revealFraction = (dayRevealProgress - dayIndex).coerceIn(0f, 1f)
        val easedReveal = LinearOutSlowInEasing.transform(revealFraction)
        val cardAlpha = easedReveal
        val cardScale = 0.94f + (0.06f * easedReveal)
        Modifier.graphicsLayer {
            alpha = cardAlpha
            scaleX = cardScale
            scaleY = cardScale
        }
    }

    Box(
        modifier = modifier
            .width(geometry.columnWidth)
            .height(geometry.cardHeight)
            .then(revealModifier)
            .semantics {
                contentDescription = day.accessibilityDescription(isLatest, isRealtime)
            },
    ) {
        if (isLatest) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = geometry.cardStartInset,
                    end = geometry.cardEndPadding,
                    top = geometry.cardTopPadding,
                    bottom = geometry.cardBottomPadding,
                ),
        ) {
            Column(
                modifier = Modifier.height(geometry.dateBlockHeight),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isRealtime) TrackingRealtimeDayLabel else day.tradeDate,
                    style = when (geometry.dateTextScale) {
                        TrackingTextScale.COMPACT -> MaterialTheme.typography.headlineSmall
                        TrackingTextScale.REGULAR -> MaterialTheme.typography.headlineMedium
                        TrackingTextScale.LARGE -> MaterialTheme.typography.headlineLarge
                    },
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        isRealtime -> "盘中实时"
                        isLatest -> "最新交易日"
                        else -> "历史交易日"
                    },
                    style = when (geometry.codeTextScale) {
                        TrackingTextScale.COMPACT -> MaterialTheme.typography.titleSmall
                        TrackingTextScale.REGULAR -> MaterialTheme.typography.titleMedium
                        TrackingTextScale.LARGE -> MaterialTheme.typography.titleLarge
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(geometry.dateBottomGap))

            TrackingSectionCard(
                title = "选股结果",
                section = StrategyTrackingSection.SELECTION,
                nodes = day.selection,
                geometry = geometry,
                tradeDate = day.tradeDate,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                overlayState = overlayState,
                isScrolling = isScrolling,
                onStockClick = { node, section -> onStockClick(node, section, day.tradeDate) },
            )
            Spacer(modifier = Modifier.height(geometry.sectionGap))
            TrackingSectionCard(
                title = "持有股票",
                section = StrategyTrackingSection.HOLDINGS,
                nodes = day.holdings,
                geometry = geometry,
                tradeDate = day.tradeDate,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                overlayState = overlayState,
                isScrolling = isScrolling,
                onStockClick = { node, section -> onStockClick(node, section, day.tradeDate) },
            )
            Spacer(modifier = Modifier.height(geometry.sectionGap))
            TrackingSectionCard(
                title = "清仓股票",
                section = StrategyTrackingSection.CLEARED,
                nodes = day.cleared,
                geometry = geometry,
                tradeDate = day.tradeDate,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                overlayState = overlayState,
                isScrolling = isScrolling,
                onStockClick = { node, section -> onStockClick(node, section, day.tradeDate) },
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TrackingSectionCard(
    title: String,
    section: StrategyTrackingSection,
    nodes: List<StrategyTrackingStockNode>,
    geometry: TimelineGeometry,
    tradeDate: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    overlayState: TrackingTimelineOverlayState,
    isScrolling: Boolean,
    onStockClick: (StrategyTrackingStockNode, StrategyTrackingSection) -> Unit = { _, _ -> },
) {
    val nodesBySlotIndex = remember(nodes) { nodes.associateBy { it.slotIndex } }
    val rowTint = when (section) {
        StrategyTrackingSection.SELECTION -> MaterialTheme.colorScheme.secondary
        StrategyTrackingSection.HOLDINGS -> MaterialTheme.colorScheme.primary
        StrategyTrackingSection.CLEARED -> MaterialTheme.colorScheme.tertiary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(geometry.spec.sectionHeight),
        verticalArrangement = Arrangement.spacedBy(geometry.slotGap),
    ) {
        Box(
            modifier = Modifier.height(geometry.sectionHeaderHeight),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(width = 8.dp, height = 8.dp),
                    shape = RoundedCornerShape(percent = 50),
                    color = rowTint
                ) {}
                Text(
                    text = title,
                    style = when (geometry.sectionTitleTextScale) {
                        TrackingTextScale.COMPACT -> MaterialTheme.typography.titleSmall
                        TrackingTextScale.REGULAR -> MaterialTheme.typography.titleMedium
                        TrackingTextScale.LARGE -> MaterialTheme.typography.titleLarge
                    },
                    color = rowTint,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        repeat(TrackingSlotCount) { slotIndex ->
            val node = nodesBySlotIndex[slotIndex]
            if (nodes.isEmpty() && slotIndex == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(geometry.slotHeight)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "空",
                        style = bodyTextStyle(geometry),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            } else {
                TrackingSlotRow(
                    node = node,
                    section = section,
                    geometry = geometry,
                    tradeDate = tradeDate,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    overlayState = overlayState,
                    isScrolling = isScrolling,
                    onClick = node?.let { { onStockClick(it, section) } } ?: {},
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TrackingSlotRow(
    node: StrategyTrackingStockNode?,
    section: StrategyTrackingSection,
    geometry: TimelineGeometry,
    tradeDate: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    overlayState: TrackingTimelineOverlayState,
    isScrolling: Boolean,
    onClick: () -> Unit = {},
) {
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow
    val surfaceMid = MaterialTheme.colorScheme.surfaceContainer

    val (tintColor, tintRatio) = when (section) {
        StrategyTrackingSection.SELECTION ->
            MaterialTheme.colorScheme.secondaryContainer to 0.08f
        StrategyTrackingSection.HOLDINGS ->
            MaterialTheme.colorScheme.primaryContainer to 0.10f
        StrategyTrackingSection.CLEARED ->
            MaterialTheme.colorScheme.tertiaryContainer to 0.06f
    }
    val hoverTintRatio = tintRatio * 2.2f

    val itemBg = remember(tintColor, surfaceLow, tintRatio) {
        mutedColor(tintColor, surfaceLow, tintRatio)
    }
    val codeColor = when (section) {
        StrategyTrackingSection.SELECTION -> MaterialTheme.colorScheme.onSecondaryContainer
        StrategyTrackingSection.HOLDINGS -> MaterialTheme.colorScheme.onPrimaryContainer
        StrategyTrackingSection.CLEARED -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    val pnlRise = MaterialTheme.colorScheme.primary
    val pnlFall = MaterialTheme.colorScheme.tertiary

    if (isScrolling) {
        TrackingStaticSlotRow(
            node = node,
            geometry = geometry,
            itemBg = itemBg,
            codeColor = codeColor,
            pnlRise = pnlRise,
            pnlFall = pnlFall,
        )
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHoveredRaw by interactionSource.collectIsHoveredAsState()
    val isHovered = isHoveredRaw

    val semanticsText = if (node == null) {
        "${section.label()}留空"
    } else {
        "${section.label()}，${node.stockName}，${node.stockCode}"
    }

    val hoverBg = remember(tintColor, surfaceMid, hoverTintRatio) {
        mutedColor(tintColor, surfaceMid, hoverTintRatio.coerceAtMost(0.35f))
    }

    val animatedBg by animateColorAsState(
        targetValue = when {
            node == null -> Color.Transparent
            isHovered -> hoverBg
            else -> itemBg
        },
        animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing),
        label = "card_bg"
    )
    val animatedScale by animateFloatAsState(
        targetValue = if (isHovered && node != null) 1.02f else 1f,
        animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing),
        label = "card_scale"
    )

    val cardKey = node?.let {
        trackingCardKey(
            tradeDate = tradeDate,
            section = section,
            stockCode = it.stockCode,
            slotIndex = it.slotIndex,
        )
    }
    val isSelectedCard = cardKey?.let { overlayState.isHidden(it) } == true

    with(sharedTransitionScope) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(geometry.slotHeight)
                .semantics { contentDescription = semanticsText }
                .hoverable(
                    interactionSource = interactionSource,
                    enabled = node != null
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node == null || cardKey == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            color = animatedBg,
                            shape = MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "—",
                        style = bodyTextStyle(geometry),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    )
                }
            } else {
                AnimatedVisibility(
                    visible = !isSelectedCard,
                    enter = EnterTransition.None,
                    exit = ExitTransition.None,
                    modifier = Modifier.weight(1f),
                    label = "tracking_slot_card_visibility"
                ) {
                    val slotAnimatedScope = this
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = animatedScale
                                scaleY = animatedScale
                            }
                            .sharedElement(
                                sharedContentState = rememberSharedContentState(cardKey),
                                animatedVisibilityScope = slotAnimatedScope,
                                boundsTransform = { _, _ ->
                                    tween(durationMillis = DetailContainerTransitionMs, easing = LinearOutSlowInEasing)
                                }
                            )
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                color = animatedBg,
                                shape = MaterialTheme.shapes.small,
                            )
                            .clickable(
                                enabled = true,
                                onClick = onClick,
                            )
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        val hasPnl = node.actualPnl != null || node.maxPnl != null
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
                                Text(
                                    text = node.stockName,
                                    style = bodyTextStyle(geometry),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.sharedBounds(
                                        sharedContentState = rememberSharedContentState(trackingStockNameKey(cardKey)),
                                        animatedVisibilityScope = slotAnimatedScope,
                                        boundsTransform = { _, _ ->
                                            tween(durationMillis = DetailContainerTransitionMs, easing = LinearOutSlowInEasing)
                                        }
                                    )
                                )
                                Text(
                                    text = node.stockCode,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = codeColor.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.sharedBounds(
                                        sharedContentState = rememberSharedContentState(trackingStockCodeKey(cardKey)),
                                        animatedVisibilityScope = slotAnimatedScope,
                                        boundsTransform = { _, _ ->
                                            tween(durationMillis = DetailContainerTransitionMs, easing = LinearOutSlowInEasing)
                                        }
                                    )
                                )
                            }
                            if (hasPnl) {
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(0.dp),
                                ) {
                                    node.actualPnl?.let {
                                        val color = if (it >= 0) pnlRise else pnlFall
                                        Text(
                                            text = formatPnlPercent(it),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                        )
                                    }
                                    node.maxPnl?.let {
                                        val color = if (it >= 0) pnlRise else pnlFall
                                        Text(
                                            text = "最高 ${formatPnlPercent(it)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color.copy(alpha = 0.65f),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackingStaticSlotRow(
    node: StrategyTrackingStockNode?,
    geometry: TimelineGeometry,
    itemBg: Color,
    codeColor: Color,
    pnlRise: Color,
    pnlFall: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(geometry.slotHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        color = Color.Transparent,
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "—",
                    style = bodyTextStyle(geometry),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                )
            }
            return@Row
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.small)
                .background(
                    color = itemBg,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(horizontal = 10.dp, vertical = 2.dp)
        ) {
            val hasPnl = node.actualPnl != null || node.maxPnl != null
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        text = node.stockName,
                        style = bodyTextStyle(geometry),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = node.stockCode,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = codeColor.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (hasPnl) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        node.actualPnl?.let {
                            val color = if (it >= 0) pnlRise else pnlFall
                            Text(
                                text = formatPnlPercent(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                        node.maxPnl?.let {
                            val color = if (it >= 0) pnlRise else pnlFall
                            Text(
                                text = "最高 ${formatPnlPercent(it)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = color.copy(alpha = 0.65f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun bodyTextStyle(
    geometry: TimelineGeometry,
): TextStyle = when (geometry.bodyTextScale) {
    TrackingTextScale.COMPACT -> MaterialTheme.typography.bodyMedium
    TrackingTextScale.REGULAR -> MaterialTheme.typography.bodyLarge
    TrackingTextScale.LARGE -> MaterialTheme.typography.headlineSmall
}

@Composable
private fun codeTextStyle(
    geometry: TimelineGeometry,
): TextStyle = when (geometry.codeTextScale) {
    TrackingTextScale.COMPACT -> MaterialTheme.typography.bodyMedium
    TrackingTextScale.REGULAR -> MaterialTheme.typography.bodyLarge
    TrackingTextScale.LARGE -> MaterialTheme.typography.titleLarge
}

@Composable
private fun TrackingLoadingState(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "正在整理策略持仓跟踪",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "正在加载最近 20 个交易日的持仓变化、调入与清仓关系。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TrackingFeedbackState(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.widthIn(max = 420.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

private fun StrategyTrackingSection.label(): String = when (this) {
    StrategyTrackingSection.SELECTION -> "选股结果"
    StrategyTrackingSection.HOLDINGS -> "持有股票"
    StrategyTrackingSection.CLEARED -> "清仓股票"
}

private fun StrategyPositionTrackingDay.accessibilityDescription(
    isLatest: Boolean,
    isRealtime: Boolean,
): String =
    buildString {
        append(if (isRealtime) TrackingRealtimeDayLabel else tradeDate)
        append("，")
        append(
            when {
                isRealtime -> "盘中实时"
                isLatest -> "最新交易日"
                else -> "历史交易日"
            }
        )
        append("，调入")
        append(selection.size)
        append("只，持仓")
        append(holdings.size)
        append("只，清仓")
        append(cleared.size)
        append("只")
    }

private fun StrategyPositionTrackingTimeline.summaryText(): String =
    "共 ${days.size} 列，持有主干 ${edges.count { it.kind == TrackingEdgeKind.HOLD_CONTINUE }} 条，调入支路 ${edges.count { it.kind == TrackingEdgeKind.ENTER_HOLDING }} 条，清仓支路 ${edges.count { it.kind == TrackingEdgeKind.EXIT_CLEAR }} 条。"

private fun StrategyPositionTrackingTimeline.accessibilityDescription(): String =
    "策略持仓跟踪时间轴。${summaryText()} 按时间从左到右展开，右侧为最新日期。"

private data class TrackingEdgePalette(
    val holdContinue: Color,
    val exitClear: Color,
    val enterHolding: Color,
)

private fun mutedColor(container: Color, surface: Color, ratio: Float): Color =
    Color(
        red = container.red * ratio + surface.red * (1f - ratio),
        green = container.green * ratio + surface.green * (1f - ratio),
        blue = container.blue * ratio + surface.blue * (1f - ratio),
    )

private fun formatPnlPercent(value: Float): String {
    val rounded = kotlin.math.round(value * 100) / 100f
    val sign = if (rounded >= 0) "+" else "-"
    val absVal = kotlin.math.abs(rounded)
    val intPart = absVal.toInt()
    val decimal = kotlin.math.round((absVal - intPart) * 100).toInt()
    val decimalStr = if (decimal < 10) "0$decimal" else "$decimal"
    return "$sign$intPart.$decimalStr%"
}
