package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import model.candle.StrategyPositionTrackingDay
import model.candle.StrategyTrackingEdgeKind
import model.candle.StrategyTrackingSection
import model.candle.StrategyTrackingStockNode
import org.shiroumi.quant_kmp.di.HttpClientProvider
import org.shiroumi.quant_kmp.data.candle.CandleRepositoryImpl
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import org.shiroumi.quant_kmp.ui.animation.AnimationDurations
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.navigation.ProvideMobileTitleBar
import org.shiroumi.quant_kmp.ui.navigation.RegisterTransientBack

private val CompactPagePadding = 16.dp
private val DefaultPagePadding = 24.dp

/**
 * 列表底部为悬浮切换按钮预留的滚动余量：仅保证末行能向上滚出按钮覆盖区并留出呼吸，
 * 不再把按钮当作占位块挤压正文。正文背景自然铺满并延伸到按钮下方，
 * 按钮以小尺寸 FAB 悬浮其上（SmallFAB 40dp + 底距 16dp = 56dp 占位，再 +12dp 呼吸）。
 */
private val TrackingPanoramaButtonReserve = 68.dp

/** Compact/Medium 列表形态的内容最大宽度，与全局 Compact contentMaxWidth 一致。 */
private val TrackingCompactListMaxWidth = 600.dp
private val HoldContinueStroke = 3.0f
private val ExitClearStroke = 2.25f
private val EnterHoldingStroke = 1.75f
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
        StrategyPositionTrackingViewModel(
            repository = CandleRepositoryImpl(
                httpClient = HttpClientProvider.apiClient,
                baseUrl = org.shiroumi.config.AppConfig.apiBaseUrl
            ),
        )
    }
) {
    val state by viewModel.state.collectAsState()
    val timeline = state.timeline
    val isLoading = state.isLoadingTracking
    val error = state.error
    val selectedStock = state.selectedStock
    val selectedDetail = state.selectedDetail
    val calibration = state.calibration
    val listObservedDate = state.listObservedDate
    DisposableEffect(viewModel) {
        viewModel.onScreenEnter()
        onDispose { viewModel.onScreenLeave() }
    }
    // 校准激活时渲染跟随者视角重放流，模型自身流仍在后台随 WS 更新
    val activeCalibration = calibration
    val displayTimeline = if (activeCalibration != null) activeCalibration.timeline else timeline
    val displayLoading = activeCalibration?.isLoading ?: isLoading
    val displayError = activeCalibration?.error ?: error
    var calibrationPickerVisible by remember { mutableStateOf(false) }
    val config = rememberAdaptiveLayoutConfig()
    val pagePadding = config.pagePadding
    var disclaimerVisible by remember { mutableStateOf(false) }
    // ≥840dp（Expanded 及以上）左列表 + 右全景流转图双栏；<840dp 列表为主、底部按钮切换全景图
    val useSplitPanes = config.isAtLeastExpanded
    var panoramaOpen by remember { mutableStateOf(false) }
    // Compact / Medium 由 TopBar 承载标题；Expanded 及以上由页内 TrackingPageHeader 大字承载
    if (!useSplitPanes) {
        ProvideMobileTitleBar(
            title = "策略持仓跟踪",
            // 叹号是标题的附属说明入口，紧贴标题文字呈现，与大屏 TrackingPageHeader 形态一致；
            // 右侧 actions 区只保留跟随校准这类页面级动作。
            titleTrailing = {
                DisclaimerIcon(
                    expanded = disclaimerVisible,
                    onClick = { disclaimerVisible = true },
                )
            },
            actions = {
                IconButton(onClick = { calibrationPickerVisible = true }) {
                    Icon(
                        imageVector = Icons.Outlined.EditCalendar,
                        contentDescription = "跟随校准",
                        tint = if (activeCalibration != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
        )
    }
    // 跨断点切换到双栏形态时收起全景图，避免缩回窄屏时直接落在全景而非列表默认形态
    LaunchedEffect(useSplitPanes) {
        if (useSplitPanes) panoramaOpen = false
    }
    var overlayAnchor by remember {
        mutableStateOf<TrackingOverlayAnchorState?>(null)
    }
    val timelineOverlayState = remember { TrackingTimelineOverlayState() }
    var detailContentVisible by remember { mutableStateOf(false) }
    val detailVisible = selectedStock != null
    // 弹层统一返回链：两个消费者无条件注册且顺序固定（返回链按注册逆序消费），
    // Back 永远先关详情浮层、再收全景图、最后回退路由；若放进断点分支，
    // 跨断点重组会重排注册顺序导致消费倒置。双栏形态下 panoramaOpen 恒 false，注册无副作用。
    RegisterTransientBack(isOpen = panoramaOpen, onClose = { panoramaOpen = false })
    RegisterTransientBack(isOpen = detailVisible, onClose = { viewModel.dispatch(StrategyTrackingAction.DismissDetail) })

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
        containerColor = MaterialTheme.colorScheme.surface,
        // 该页嵌套在外层导航 Scaffold（已含 MobileNavTitleBar + statusBarsPadding 完成顶部避让）之内。
        // 内层 Scaffold 无 topBar，若沿用默认 contentWindowInsets=systemBars 会把顶部安全区 inset
        // 二次计入，iOS 刘海/灵动岛下重复约 44pt，表现为标题栏下方一整条空白。置 0 交由外层统一避让，
        // 与 AgentAnalysisScreen 同结构页面的处理对齐。
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
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
                        val onStockClick: (StrategyTrackingStockNode, StrategyTrackingSection, String) -> Unit =
                            { node, section, tradeDate -> viewModel.dispatch(StrategyTrackingAction.SelectStock(node, section, tradeDate)) }
                        Box(modifier = Modifier.fillMaxSize()) {
                            val contentTimeline = displayTimeline?.takeIf { it.days.isNotEmpty() }
                            if (contentTimeline == null) {
                                // 反馈态（加载中 / 无数据 / 加载失败）统一在内容区居中渲染一次，
                                // 短路掉双栏与 Compact 两套布局，从根上消除左右子面板各渲染一份反馈的重复。
                                // 校准失败且无数据时无法靠「重新加载」自救（那是重拉模型自身流），
                                // 故此场景的重试动作改为「清除校准」回到必有数据的模型流，避免卡死。
                                val calibrationFailed = activeCalibration?.error?.isNotBlank() == true
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (displayLoading) {
                                        TrackingLoadingState()
                                    } else {
                                        TrackingFeedbackState(
                                            title = if (displayError.isNullOrBlank()) {
                                                "暂无策略持仓跟踪数据"
                                            } else {
                                                "策略持仓跟踪加载失败"
                                            },
                                            message = displayError
                                                ?: "策略审计数据生成后，这里会展示最近 20 个交易日的持仓变化树。",
                                            actionLabel = if (calibrationFailed) "返回模型完整持仓流" else "重新加载",
                                            onAction = {
                                                viewModel.dispatch(
                                                    if (calibrationFailed) {
                                                        StrategyTrackingAction.ClearCalibration
                                                    } else {
                                                        StrategyTrackingAction.Refresh
                                                    }
                                                )
                                            },
                                        )
                                    }
                                }
                            } else if (useSplitPanes) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.lg),
                                ) {
                                    TrackingPageHeader(
                                        disclaimerExpanded = disclaimerVisible,
                                        onDisclaimerToggle = { disclaimerVisible = !disclaimerVisible },
                                        calibration = activeCalibration,
                                        onOpenCalibrationPicker = { calibrationPickerVisible = true },
                                        onClearCalibration = { viewModel.dispatch(StrategyTrackingAction.ClearCalibration) },
                                    )
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.lg),
                                    ) {
                                        TrackingOverviewListPanel(
                                            timeline = contentTimeline,
                                            onStockClick = onStockClick,
                                            observedDate = listObservedDate,
                                            onObservedDateChange = { viewModel.dispatch(StrategyTrackingAction.SelectListObservedDate(it)) },
                                            modifier = Modifier
                                                .width(AgentTheme.Sizing.sidePanelWidthMax)
                                                .fillMaxHeight(),
                                        )
                                        TrackingTimelineScaffold(
                                            timeline = contentTimeline,
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = timelineAnimatedScope,
                                            overlayState = timelineOverlayState,
                                            onStockClick = onStockClick,
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                        )
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    activeCalibration?.let {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = AgentTheme.Spacing.sm, bottom = AgentTheme.Spacing.sm),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            TrackingCalibrationControl(
                                                calibration = it,
                                                onOpenPicker = { calibrationPickerVisible = true },
                                                onClear = { viewModel.dispatch(StrategyTrackingAction.ClearCalibration) },
                                            )
                                        }
                                    }
                                    AnimatedContent(
                                        targetState = panoramaOpen,
                                        transitionSpec = {
                                            fadeIn(tween(AnimationDurations.NORMAL, easing = LinearOutSlowInEasing)) togetherWith
                                                fadeOut(tween(AnimationDurations.FAST, easing = LinearOutSlowInEasing))
                                        },
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        label = "tracking_compact_mode",
                                    ) { showPanorama ->
                                        if (showPanorama) {
                                            TrackingTimelineScaffold(
                                                timeline = contentTimeline,
                                                sharedTransitionScope = this@SharedTransitionLayout,
                                                animatedVisibilityScope = timelineAnimatedScope,
                                                overlayState = timelineOverlayState,
                                                onStockClick = onStockClick,
                                                // 全景流转图铺满整个内容区，正文自然延伸到悬浮切换按钮下方；
                                                // 底部呼吸由 TrackingTimelineContent 自身的 lazyRowBottom 负责。
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Box(modifier = Modifier.fillMaxSize()) {
                                                TrackingOverviewListPanel(
                                                    timeline = contentTimeline,
                                                    onStockClick = onStockClick,
                                                    observedDate = listObservedDate,
                                                    onObservedDateChange = { viewModel.dispatch(StrategyTrackingAction.SelectListObservedDate(it)) },
                                                    // 列表底部为全景图按钮预留占位空间，避免末行被常驻按钮遮挡
                                                    bottomContentPadding = TrackingPanoramaButtonReserve,
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .widthIn(max = TrackingCompactListMaxWidth)
                                                        .align(Alignment.TopCenter),
                                                )
                                            }
                                        }
                                    }
                                }
                                SmallFloatingActionButton(
                                    onClick = { panoramaOpen = !panoramaOpen },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    elevation = FloatingActionButtonDefaults.elevation(
                                        defaultElevation = 3.dp,
                                        pressedElevation = 3.dp,
                                    ),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = AgentTheme.Spacing.md),
                                ) {
                                    Icon(
                                        imageVector = if (panoramaOpen) {
                                            Icons.AutoMirrored.Outlined.ViewList
                                        } else {
                                            Icons.Outlined.AccountTree
                                        },
                                        contentDescription = if (panoramaOpen) "切换到列表" else "切换到全景图",
                                    )
                                }
                            }

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
                                        onDismiss = { viewModel.dispatch(StrategyTrackingAction.DismissDetail) },
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

            if (calibrationPickerVisible) {
                // 与跟踪说明同款分形态：Compact/Medium 用底部抽屉（滑入点选即收，贴合单手），
                // Expanded 及以上用居中 Dialog（与桌面工作台形态一致，避免大屏底部抽屉横跨整屏）。
                // 可选日期取模型自身流 timeline（集合不随校准收缩），而非校准视图。
                val useBottomSheet = config.isCompact || config.isMedium
                if (useBottomSheet) {
                    // 三条收起路径（点交易日 / 点跟随全程 / 点关闭）都由抽屉内部统一先播放
                    // hide() 下滑动画再回调，故这里只传纯业务动作与关闭信号，不各自置 visible。
                    CalibrationPickerBottomSheet(
                        selectedDate = activeCalibration?.followStartDate,
                        timeline = timeline,
                        onPick = { viewModel.dispatch(StrategyTrackingAction.CalibrateFollowStart(it)) },
                        onClearCalibration = { viewModel.dispatch(StrategyTrackingAction.ClearCalibration) },
                        onDismiss = { calibrationPickerVisible = false },
                    )
                } else {
                    CalibrationPickerDialog(
                        selectedDate = activeCalibration?.followStartDate,
                        timeline = timeline,
                        onPick = { viewModel.dispatch(StrategyTrackingAction.CalibrateFollowStart(it)) },
                        onClearCalibration = { viewModel.dispatch(StrategyTrackingAction.ClearCalibration) },
                        onDismiss = { calibrationPickerVisible = false },
                    )
                }
            }
        }
    }
}

/**
 * 全景流转图脚手架。仅在 timeline 非空且有数据时被调用（反馈态由
 * [StrategyPositionTrackingScreen] 在内容区统一短路渲染），故此处不再判 loading/error。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TrackingTimelineScaffold(
    timeline: StrategyPositionTrackingTimeline,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    overlayState: TrackingTimelineOverlayState,
    onStockClick: (StrategyTrackingStockNode, StrategyTrackingSection, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = rememberAdaptiveLayoutConfig()
    val layoutSpec = remember(config) { trackingTimelineLayoutSpec(config) }
    var hasAutoScrolled by remember { mutableStateOf(false) }
    var pageVisible by remember { mutableStateOf(false) }
    var shouldAnimateTimelineSequence by remember { mutableStateOf(true) }
    var revealedDayCount by remember { mutableStateOf(0) }
    var revealedConnectorCount by remember { mutableStateOf(0) }
    var revealStartDayIndex by remember { mutableStateOf(0) }
    var revealStartConnectorIndex by remember { mutableStateOf(0) }
    val timelineAlpha by animateFloatAsState(
        targetValue = if (pageVisible) 1f else 0f,
        animationSpec = tween(durationMillis = TimelineEnterDurationMs, delayMillis = 60, easing = LinearOutSlowInEasing),
        label = "tracking_timeline_alpha"
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

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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

@Composable
private fun TrackingPageHeader(
    disclaimerExpanded: Boolean,
    onDisclaimerToggle: () -> Unit,
    calibration: TrackingCalibration? = null,
    onOpenCalibrationPicker: () -> Unit = {},
    onClearCalibration: () -> Unit = {},
) {
    val config = rememberAdaptiveLayoutConfig()
    val titleStyle = when {
        config.isExpanded || config.isLarge || config.isXLarge -> MaterialTheme.typography.displaySmall
        config.isMedium -> MaterialTheme.typography.headlineLarge
        else -> MaterialTheme.typography.headlineMedium
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm),
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
        Spacer(modifier = Modifier.weight(1f))
        TrackingCalibrationControl(
            calibration = calibration,
            onOpenPicker = onOpenCalibrationPicker,
            onClear = onClearCalibration,
        )
    }
}

/**
 * 最早跟随日校准控件。未激活时为入口按钮；激活时为状态胶囊
 * （点胶囊主体重新选日期，点尾部关闭图标回到模型完整持仓流）。
 */
@Composable
private fun TrackingCalibrationControl(
    calibration: TrackingCalibration?,
    onOpenPicker: () -> Unit,
    onClear: () -> Unit,
) {
    if (calibration == null) {
        FilledTonalButton(onClick = onOpenPicker) {
            Icon(
                imageVector = Icons.Outlined.EditCalendar,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("跟随校准")
        }
        return
    }
    val hasError = !calibration.error.isNullOrBlank()
    val containerColor =
        if (hasError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer
    val onContainerColor =
        if (hasError) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .clickable(onClick = onOpenPicker)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (hasError) Icons.Outlined.ErrorOutline else Icons.Outlined.EditCalendar,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = onContainerColor,
            )
            Text(
                text = if (hasError) "校准失败 · 点此重选" else "从 ${calibration.followStartDate} 起跟随",
                style = MaterialTheme.typography.labelLarge,
                color = onContainerColor,
            )
            if (calibration.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = onContainerColor,
                )
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "清除跟随校准",
                    modifier = Modifier.size(16.dp),
                    tint = onContainerColor,
                )
            }
        }
    }
}

private const val CalibrationPickerHint =
    "选择你第一笔跟随买入发生的交易日。系统以该日空仓起步，按生产持仓规则重放之后的买入与卖出，" +
        "排除模型在你跟随之前已有持仓的影响，使跟踪流与你的真实持仓对齐。" +
        "校准视图仅包含已确认交易日，不含盘中实时列。"

/**
 * 校准日期选择列表。顶部固定说明，其下「跟随全程」与全部可校准交易日同款行渲染，
 * 点击即终态（外层负责落库并收起抽屉），无确认按钮、无文本校验——列表只枚举合法项。
 */
@Composable
private fun CalibrationDateList(
    selectedDate: String?,
    tradeDates: List<String>,
    onPick: (String) -> Unit,
    onClearCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.xs),
    ) {
        item(key = "calibration-hint") {
            Text(
                text = CalibrationPickerHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AgentTheme.Spacing.sm),
            )
        }
        item(key = "calibration-follow-all") {
            CalibrationDateRow(
                label = "跟随全程",
                description = "模型完整持仓流",
                selected = selectedDate == null,
                onClick = onClearCalibration,
            )
        }
        items(items = tradeDates, key = { it }) { date ->
            CalibrationDateRow(
                label = date,
                description = null,
                selected = date == selectedDate,
                onClick = { onPick(date) },
            )
        }
    }
}

@Composable
private fun CalibrationDateRow(
    label: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AgentTheme.Spacing.md, vertical = AgentTheme.Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 跟随校准底部抽屉。全尺寸统一形态，复用 [DisclaimerBottomSheet] 的 canonical 样式
 * （自定义 dragHandle + hide() 协程 dismiss + 标题行 + 分隔线），内容区为 [CalibrationDateList]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalibrationPickerBottomSheet(
    selectedDate: String?,
    timeline: StrategyPositionTrackingTimeline?,
    onPick: (String) -> Unit,
    onClearCalibration: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    // 统一收起：先播放 hide() 下滑动画，再执行业务动作并通知外层关闭。
    // 点交易日、点跟随全程、点关闭三条路径共用，保证动画一致。
    val dismissWith: (() -> Unit) -> Unit = { action ->
        scope.launch {
            sheetState.hide()
            action()
            onDismiss()
        }
    }
    val tradeDates = remember(timeline) {
        timeline?.calibratableTradeDates().orEmpty()
    }
    ModalBottomSheet(
        onDismissRequest = { dismissWith {} },
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
                text = "跟随校准",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = { dismissWith {} }) {
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
        CalibrationDateList(
            selectedDate = selectedDate,
            tradeDates = tradeDates,
            onPick = { date -> dismissWith { onPick(date) } },
            onClearCalibration = { dismissWith(onClearCalibration) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * 跟随校准 Dialog（大屏形态）。与 [CalibrationPickerBottomSheet] 共用 [CalibrationDateList]：
 * 顶部说明 + 「跟随全程」+ 可校准交易日列表，点选即终态（先执行业务动作再收起 Dialog），
 * 无确认按钮。容器与 [DisclaimerDialog] 同款 surfaceContainerLow，对齐大屏弹窗规格。
 */
@Composable
private fun CalibrationPickerDialog(
    selectedDate: String?,
    timeline: StrategyPositionTrackingTimeline?,
    onPick: (String) -> Unit,
    onClearCalibration: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tradeDates = remember(timeline) {
        timeline?.calibratableTradeDates().orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "跟随校准",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            CalibrationDateList(
                selectedDate = selectedDate,
                tradeDates = tradeDates,
                onPick = { date -> onPick(date); onDismiss() },
                onClearCalibration = { onClearCalibration(); onDismiss() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
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

/** 操作策略说明的标题文案，叹号弹窗 / 抽屉共用。 */
private const val StrategyGuideTitle = "操作策略"

/** 一组操作说明：组标题 + 若干条目。 */
private data class StrategyGuideGroup(
    val title: String,
    val items: List<String>,
)

/**
 * 叹号弹窗的操作策略说明。从用户视角讲清「这套跟踪每天怎么买、怎么卖」，
 * 数值与真实持仓规则一致（次日开盘买入 / 跳空超 3% 放弃 / 每天最多买 3 只 /
 * 涨到 +8% 止盈 / 买入后头两个交易日触及 +2.5% 落袋 / 买入后第 2 个交易日收盘到期清仓），
 * 不暴露任何选股模型或算法细节。机制说明在前，风险提示在后。
 *
 * 天数口径统一为「买入后第 N 个交易日」（买入当天不计）：买入后第 1、2 个交易日是保盈窗口，
 * 买入后第 2 个交易日收盘即到期强制清仓——它也是保盈窗口的最后一天，二者是同一天。
 */
private val StrategyGuideGroups = listOf(
    StrategyGuideGroup(
        title = "什么时候买",
        items = listOf(
            "每天收盘后产出次日的选股结果，第二天开盘时买入。",
            "如果某只票开盘价比前一天收盘高出 3% 以上，说明已经追高，当天放弃买入。",
            "一天最多买入 3 只，按当日评分从高到低优先成交。",
        ),
    ),
    StrategyGuideGroup(
        title = "什么时候卖",
        items = listOf(
            "盘中涨到买入价的 +8% 就止盈卖出，这是最优先的卖出条件。",
            "买入后第 1、2 个交易日里，只要盘中摸到 +2.5% 就先落袋保住浮盈。",
            "最迟持有到买入后第 2 个交易日，当天收盘没卖出的一律清仓，不恋战。",
        ),
    ),
    StrategyGuideGroup(
        title = "风险提示",
        items = listOf(
            "选股与跟踪结果仅供参考，不构成投资建议。",
            "次日大幅低开时，建议观望而非追入。",
            "入选当日已大幅偏离成本区时，追高风险较大。",
            "潜在追高空间接近或超过 50% 时，建议谨慎买入。",
        ),
    ),
)

@Composable
private fun DisclaimerList(
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.md),
    ) {
        StrategyGuideGroups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                group.items.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.sm)) {
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
                text = StrategyGuideTitle,
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
                // 分组说明已扩成 13 行，小屏 / 横屏下高度可能超过 sheet 可用区；
                // ModalBottomSheet 的 ColumnScope 默认不滚动，必须限高 + 自滚保证末条可达，
                // 与 CalibrationPickerBottomSheet 同款长内容处理对齐。
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
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
                text = StrategyGuideTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            DisclaimerList(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
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
    // 流转边盈亏标签：盈亏数值由云端计算并随边下发，这里按边业务键回连布局结果
    val edgeLabels = remember(timeline, edgeLayout) {
        val labelByKey = timeline.edges.associateBy({ it.lookupKey() }, { it.toLabelData() })
        val tradeDates = timeline.days.map { it.tradeDate }
        edgeLayout.indexedEdges.map { indexed ->
            val fromDate = tradeDates.getOrNull(indexed.fromIndex)
            val toDate = tradeDates.getOrNull(indexed.toIndex)
            if (fromDate == null || toDate == null) {
                null
            } else {
                labelByKey[
                    "$fromDate|${indexed.fromSection}|${indexed.fromSlotIndex}|" +
                        "$toDate|${indexed.toSection}|${indexed.toSlotIndex}|${indexed.kind}"
                ]
            }
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
                                isFollowStart = day.tradeDate == timeline.followStartDate,
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
                        edgeLabels = edgeLabels,
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
                            isFollowStart = day.tradeDate == timeline.followStartDate,
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
                    edgeLabels = edgeLabels,
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
    edgeLabels: List<TrackingEdgeLabelData?>,
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

    // 带原始下标分组，便于绘制时取同位置的盈亏标签
    val edgesByFromIndex = remember(indexedEdges) {
        indexedEdges.withIndex().groupBy { it.value.fromIndex }
    }

    // 标签文本预测量：边集合不变时不重复 measure，Canvas 每帧只做绘制
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val measuredLabels: List<TextLayoutResult?> = remember(edgeLabels, labelStyle, textMeasurer) {
        edgeLabels.map { label ->
            label?.let { textMeasurer.measure(AnnotatedString(it.text), labelStyle) }
        }
    }
    val labelRise = MaterialTheme.colorScheme.primary
    val labelFall = MaterialTheme.colorScheme.tertiary
    val labelNeutral = MaterialTheme.colorScheme.onSurfaceVariant
    val labelPillColor = MaterialTheme.colorScheme.surfaceContainerHigh

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

        val labelPadHPx = 6.dp.toPx()
        val labelPadVPx = 2.dp.toPx()
        val labelCornerPx = 6.dp.toPx()

        val fromIndexStart = (visibleStart - 1).coerceAtLeast(0)
        for (fromIndex in fromIndexStart..visibleEnd) {
            val edges = edgesByFromIndex[fromIndex]
            if (edges.isNullOrEmpty()) continue
            for ((edgeOrdinal, indexedEdge) in edges) {
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
                    StrategyTrackingEdgeKind.HOLD_CONTINUE -> holdContinueBase
                    StrategyTrackingEdgeKind.EXIT_CLEAR -> exitClearBase
                    StrategyTrackingEdgeKind.ENTER_HOLDING -> enterHoldingBase
                }
                val color = baseColor.copy(alpha = baseColor.alpha * connectorAlpha)
                val stroke = when (indexedEdge.kind) {
                    StrategyTrackingEdgeKind.HOLD_CONTINUE -> holdContinueStroke
                    StrategyTrackingEdgeKind.EXIT_CLEAR -> exitClearStroke
                    StrategyTrackingEdgeKind.ENTER_HOLDING -> enterHoldingStroke
                }
                drawPath(
                    path = reusablePath,
                    color = color,
                    style = stroke,
                )

                // 盈亏标签：贝塞尔曲线 t=0.5 处即两端点中点（对称控制点），胶囊底 + 方向色文本
                val labelLayout = measuredLabels.getOrNull(edgeOrdinal)
                val labelData = edgeLabels.getOrNull(edgeOrdinal)
                if (labelLayout != null && labelData != null) {
                    val midX = (start.x + end.x) / 2f
                    val midY = (start.y + end.y) / 2f
                    val textWidth = labelLayout.size.width.toFloat()
                    val textHeight = labelLayout.size.height.toFloat()
                    drawRoundRect(
                        color = labelPillColor.copy(alpha = 0.88f * connectorAlpha),
                        topLeft = Offset(
                            midX - textWidth / 2f - labelPadHPx,
                            midY - textHeight / 2f - labelPadVPx,
                        ),
                        size = Size(textWidth + labelPadHPx * 2, textHeight + labelPadVPx * 2),
                        cornerRadius = CornerRadius(labelCornerPx, labelCornerPx),
                    )
                    val labelColor = when (labelData.positive) {
                        true -> labelRise
                        false -> labelFall
                        null -> labelNeutral
                    }
                    drawText(
                        textLayoutResult = labelLayout,
                        color = labelColor.copy(alpha = connectorAlpha),
                        topLeft = Offset(midX - textWidth / 2f, midY - textHeight / 2f),
                    )
                }
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
    isFollowStart: Boolean,
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
                        isFollowStart -> "跟随起点"
                        isLatest -> "最新交易日"
                        else -> "历史交易日"
                    },
                    style = when (geometry.codeTextScale) {
                        TrackingTextScale.COMPACT -> MaterialTheme.typography.titleSmall
                        TrackingTextScale.REGULAR -> MaterialTheme.typography.titleMedium
                        TrackingTextScale.LARGE -> MaterialTheme.typography.titleLarge
                    },
                    color = if (isFollowStart && !isRealtime) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
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
                            TrackingSlotPnlColumn(node = node, pnlRise = pnlRise, pnlFall = pnlFall)
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
                TrackingSlotPnlColumn(node = node, pnlRise = pnlRise, pnlFall = pnlFall)
            }
        }
    }
}

/**
 * 槽位右侧盈亏列。清仓节点优先展示规则口径已实现收益与离场原因
 * （止盈/保盈按触价、到期按收盘，云端 HoldingStateMachine 判决重建）；
 * 持有节点展示浮动收益与持有期最高收益。
 */
@Composable
private fun TrackingSlotPnlColumn(
    node: StrategyTrackingStockNode,
    pnlRise: Color,
    pnlFall: Color,
) {
    val primaryPnl = node.exitPnl ?: node.actualPnl
    val secondaryText = node.exitReason?.label()
        ?: node.maxPnl?.let { "最高 ${formatPnlPercent(it)}" }
    if (primaryPnl == null && secondaryText == null) return
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        primaryPnl?.let {
            val color = if (it >= 0) pnlRise else pnlFall
            Text(
                text = formatPnlPercent(it),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        secondaryText?.let {
            val color = primaryPnl?.let { pnl -> if (pnl >= 0) pnlRise else pnlFall } ?: pnlRise
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.65f),
                maxLines = 1,
            )
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
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.md)
    ) {
        CircularProgressIndicator(
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text(
            text = "正在整理策略持仓跟踪",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "正在加载最近 20 个交易日的持仓变化、调入与清仓关系",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 反馈态（无数据 / 加载失败）：纯文字标题 + 说明 + 重试按钮，无卡片无边框。
 * 与 [TrackingLoadingState] 同为内容区居中的原生 Material3 反馈，全页只渲染一份。
 */
@Composable
private fun TrackingFeedbackState(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = AgentTheme.Sizing.sidePanelWidthMax),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AgentTheme.Spacing.md)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onAction) {
            Text(actionLabel)
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
    "共 ${days.size} 列，持有主干 ${edges.count { it.kind == StrategyTrackingEdgeKind.HOLD_CONTINUE }} 条，调入支路 ${edges.count { it.kind == StrategyTrackingEdgeKind.ENTER_HOLDING }} 条，清仓支路 ${edges.count { it.kind == StrategyTrackingEdgeKind.EXIT_CLEAR }} 条。"

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
