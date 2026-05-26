package org.shiroumi.quant_kmp.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import org.shiroumi.quant_kmp.MultiPlatform
import org.shiroumi.quant_kmp.NavDest
import org.shiroumi.quant_kmp.platform.downloadApk
import org.shiroumi.quant_kmp.platform.isAndroidPlatform
import org.shiroumi.quant_kmp.service.DataUpdateService
import org.shiroumi.quant_kmp.service.GlobalWebSocketClient
import org.shiroumi.quant_kmp.ui.agent.sidebar.AgentDesktopOverlay
import org.shiroumi.quant_kmp.ui.agent.sidebar.AgentSidebar
import org.shiroumi.quant_kmp.ui.agent.sidebar.AgentSidebarContent
import org.shiroumi.quant_kmp.ui.agent.sidebar.AgentSidebarState
import org.shiroumi.quant_kmp.ui.agent.sidebar.AgentSidebarViewModel
import org.shiroumi.quant_kmp.ui.agent.sidebar.LocalAgentSidebarState
import org.shiroumi.quant_kmp.ui.agent.sidebar.LocalAgentSidebarViewModel
import org.shiroumi.quant_kmp.ui.agent.viewmodel.AgentViewModel
import org.shiroumi.quant_kmp.ui.components.AppMenu
import org.shiroumi.quant_kmp.ui.components.BrandLogoCompact
import org.shiroumi.quant_kmp.ui.components.DataUpdateStatusIndicator
import org.shiroumi.quant_kmp.ui.components.ToastHost
import org.shiroumi.quant_kmp.ui.components.ToastHostState
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.core.backhandler.PlatformBrowserBackHandler
import org.shiroumi.quant_kmp.ui.core.backhandler.PlatformBackHandler
import org.shiroumi.quant_kmp.ui.core.lifecycle.PlatformLifecycleEffect
import org.shiroumi.quant_kmp.ui.core.viewmodel.LocalAgentViewModel
import org.shiroumi.config.AppConfig

private val NavDest.label: String
    get() = when (this) {
        is NavDest.Candle -> "行情"
        is NavDest.Sentiment -> "情绪"
        is NavDest.PositionTracking -> "跟踪"
        is NavDest.AgentResults -> "分析"
        is NavDest.Settings -> "设置"
    }

private val NavDest.icon: ImageVector
    get() = when (this) {
        is NavDest.Candle -> Icons.Outlined.CandlestickChart
        is NavDest.Sentiment -> Icons.Outlined.Insights
        is NavDest.PositionTracking -> Icons.Outlined.ShowChart
        is NavDest.AgentResults -> Icons.Outlined.AutoStories
        is NavDest.Settings -> Icons.Outlined.Settings
    }

private val compactNavDests = listOf(
    NavDest.Candle(),
    NavDest.Sentiment,
    NavDest.PositionTracking,
    NavDest.AgentResults(),
    NavDest.Settings,
)
private val railNavDests = listOf(
    NavDest.Candle(),
    NavDest.Sentiment,
    NavDest.PositionTracking,
    NavDest.AgentResults(),
    NavDest.Settings,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigationSuite(
    navigationState: NavigationState,
    navigator: Navigator,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
    toastHostState: ToastHostState,
    agentViewModel: AgentViewModel,
    agentState: org.shiroumi.quant_kmp.ui.agent.state.AgentContract.State,
    sidebarSelectedStock: model.candle.StockInfo? = null
) {
    val config = rememberAdaptiveLayoutConfig()
    val currentRoute = navigationState.currentRoute

    // Agent Sidebar 状态管理
    val shouldAutoCollapse = config.windowWidth < 1750.dp

    // 桌面端 AgentSidebarViewModel
    val desktopAgentSidebarViewModel = remember {
        AgentSidebarViewModel(AgentSidebarState(isExpanded = !shouldAutoCollapse))
    }
    val desktopAgentSidebarState by desktopAgentSidebarViewModel.state.collectAsState()

    LaunchedEffect(shouldAutoCollapse) {
        desktopAgentSidebarViewModel.setExpanded(!shouldAutoCollapse)
    }

    // Overlay 模式下的展开状态（窄屏折叠后）
    var overlayExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(shouldAutoCollapse) {
        if (!shouldAutoCollapse) overlayExpanded = false
    }

    // 移动端/平板端 AgentSidebarViewModel（默认折叠为 FAB）
    val mobileAgentSidebarViewModel = viewModel { AgentSidebarViewModel(AgentSidebarState.forMobile()) }
    val mobileAgentSidebarState by mobileAgentSidebarViewModel.state.collectAsState()

    val mobileTitleBarController = rememberMobileTitleBarController()

    val unreadCount = agentState.messages.count { it.role == "assistant" && !it.isThinking && it.content.isNotBlank() }

    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    val isCompactLayout = layoutType == androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.NavigationBar
    // Compact / Medium 共用 MobileNavTitleBar，Expanded 保持桌面工作台形态无 TopBar。
    // Controller 在所有尺寸下都注入，让各 page 的 ProvideMobileTitleBar 行为对外保持一致。
    CompositionLocalProvider(
        LocalAgentViewModel provides agentViewModel,
        LocalAgentSidebarViewModel provides if (config.isCompact || config.isMedium) mobileAgentSidebarViewModel else desktopAgentSidebarViewModel,
        LocalAgentSidebarState provides if (config.isCompact || config.isMedium) mobileAgentSidebarState else desktopAgentSidebarState,
        LocalMobileTitleBarController provides mobileTitleBarController,
    ) {
        PlatformBrowserBackHandler(
            enabled = navigationState.browserBackDepth > 0,
            backStackDepth = navigationState.browserBackDepth,
        ) {
            navigator.goBack()
        }

        NavigationSuiteScaffold(
            navigationItems = {
                val destinations = if (isCompactLayout) compactNavDests else railNavDests
                destinations.forEach { dest ->
                    NavigationSuiteItem(
                        selected = currentRoute?.let { it::class == dest::class } ?: false,
                        onClick = { navigator.navigate(dest) },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(dest.label) },
                        navigationSuiteType = layoutType,
                    )
                }
            },
            navigationSuiteType = layoutType,
            // Rail / Drawer 形态在垂直导航顶部显示品牌 logo；NavigationBar 不显示。
            // header slot 没有默认 padding，需自己提供与旧版一致的上下间距，避免 logo 紧贴 Rail 顶部和首个导航项。
            primaryActionContent = if (layoutType != NavigationSuiteType.NavigationBar) {
                {
                    Column(
                        modifier = Modifier.padding(top = 16.dp, bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BrandLogoCompact()
                    }
                }
            } else {
                {}
            },
        ) {
            when {
                config.isCompact -> CompactContent(
                    navigationState = navigationState,
                    navigator = navigator,
                    entryProvider = entryProvider,
                    toastHostState = toastHostState,
                    agentViewModel = agentViewModel,
                    agentState = agentState,
                    agentSidebarViewModel = mobileAgentSidebarViewModel,
                    agentSidebarState = mobileAgentSidebarState,
                    sidebarSelectedStock = sidebarSelectedStock,
                    currentRoute = currentRoute,
                    unreadCount = unreadCount,
                    mobileTitleBarController = mobileTitleBarController,
                )
                else -> ExpandedContent(
                    navigationState = navigationState,
                    navigator = navigator,
                    entryProvider = entryProvider,
                    toastHostState = toastHostState,
                    agentViewModel = agentViewModel,
                    agentState = agentState,
                    agentSidebarViewModel = desktopAgentSidebarViewModel,
                    agentSidebarState = desktopAgentSidebarState,
                    sidebarSelectedStock = sidebarSelectedStock,
                    config = config,
                    shouldAutoCollapse = shouldAutoCollapse,
                    overlayExpanded = overlayExpanded,
                    onOverlayExpandedChange = { overlayExpanded = it },
                    unreadCount = unreadCount,
                    enableTopBar = config.isMedium,
                    currentRoute = currentRoute,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactContent(
    navigationState: NavigationState,
    navigator: Navigator,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
    toastHostState: ToastHostState,
    agentViewModel: AgentViewModel,
    agentState: org.shiroumi.quant_kmp.ui.agent.state.AgentContract.State,
    agentSidebarViewModel: AgentSidebarViewModel,
    agentSidebarState: org.shiroumi.quant_kmp.ui.agent.sidebar.AgentSidebarState,
    sidebarSelectedStock: model.candle.StockInfo?,
    currentRoute: NavKey?,
    unreadCount: Int,
    mobileTitleBarController: MobileTitleBarController,
) {
    Scaffold(
        topBar = {
            MobileNavTitleBar(
                canGoBack = navigationState.canGoBack,
                onBack = { navigator.goBack() },
                defaultTitle = (currentRoute as? NavDest)?.label ?: "Quant",
                globalActions = {
                    DataUpdateStatusIndicator()
                    AppMenu(
                        canDownloadApk = !isAndroidPlatform(),
                        onDownloadApk = { downloadApk("${AppConfig.apiBaseUrl}/api/download/apk") },
                    )
                },
                agentExpanded = agentSidebarState.isExpanded,
                onAgentCollapse = { agentSidebarViewModel.toggleExpand() },
            )
        },
    ) { paddingValues ->
        PlatformBackHandler(enabled = agentSidebarState.isExpanded) {
            agentSidebarViewModel.toggleExpand()
        }
        val contentModifier = mobileTitleBarController.spec?.scrollBehavior?.let { scrollBehavior ->
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(paddingValues)
        } ?: Modifier
            .fillMaxSize()
            .padding(paddingValues)

        Box(modifier = contentModifier) {
            NavDisplay(
                entries = navigationState.toEntries(entryProvider),
                sceneStrategies = listOf(rememberListDetailSceneStrategy<NavKey>()),
                onBack = { navigator.goBack() }
            )
            ToastHost(hostState = toastHostState, modifier = Modifier.align(Alignment.BottomCenter))

            val agentOnConnect = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.Connect) }
            org.shiroumi.quant_kmp.ui.agent.sidebar.AgentFloatingCard(
                isExpanded = agentSidebarState.isExpanded,
                isProcessing = agentState.isProcessing,
                connectionStatus = agentState.connectionStatus,
                unreadCount = unreadCount,
                onToggleExpand = { agentSidebarViewModel.toggleExpand() },
                onConnect = agentOnConnect
            ) {
                AgentSidebarContent(
                    state = agentState,
                    selectedStock = sidebarSelectedStock,
                    onSendMessage = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.SendMessage(analysisType = it)) },
                    onUpdateInput = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.UpdateInput(it)) },
                    onApproveTool = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.ApproveTool(it)) },
                    onRejectTool = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.RejectTool(it)) },
                    onStopAgent = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.StopAgent) },
                    onResumeAgent = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.ResumeAgent) },
                    onConnect = agentOnConnect,
                    onNewSession = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.NewSession) },
                    isCompactExpanded = agentSidebarState.isExpanded
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedContent(
    navigationState: NavigationState,
    navigator: Navigator,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
    toastHostState: ToastHostState,
    agentViewModel: AgentViewModel,
    agentState: org.shiroumi.quant_kmp.ui.agent.state.AgentContract.State,
    agentSidebarViewModel: AgentSidebarViewModel,
    agentSidebarState: org.shiroumi.quant_kmp.ui.agent.sidebar.AgentSidebarState,
    sidebarSelectedStock: model.candle.StockInfo?,
    config: org.shiroumi.quant_kmp.ui.core.adaptive.m3.AdaptiveLayoutConfig,
    shouldAutoCollapse: Boolean,
    overlayExpanded: Boolean,
    onOverlayExpandedChange: (Boolean) -> Unit,
    unreadCount: Int,
    enableTopBar: Boolean,
    currentRoute: NavKey?,
) {
    // 当主内容区可用宽度不足以容纳"列表(260dp) + 中缝 + K 线主区(≥ 480dp) + 边距"时，
    // 强制 list/detail 单栏（避免列表被压缩到无法正常显示）。
    // 主内容区可用宽度 ≈ windowWidth - NavigationRail(80dp) - AgentSidebar(展开 360dp / 折叠 0dp)
    val agentSidebarReservedWidth = if (agentSidebarState.isExpanded && !shouldAutoCollapse) 360.dp else 0.dp
    val mainContentWidth = config.windowWidth - 80.dp - agentSidebarReservedWidth
    val twoPaneMinWidth = 800.dp
    val baseDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
    // 把 pane 之间的横向间距收敛到 0，让 list/detail 各自的 padding 完整控制中缝，
    // 避免 directive 默认 spacer 与 pane 内的 padding 叠加成大于 24dp 的留白。
    val sceneDirective = if (mainContentWidth < twoPaneMinWidth) {
        baseDirective.copy(
            maxHorizontalPartitions = 1,
            horizontalPartitionSpacerSize = 0.dp
        )
    } else {
        baseDirective.copy(horizontalPartitionSpacerSize = 0.dp)
    }
    val sceneStrategy = rememberListDetailSceneStrategy<NavKey>(directive = sceneDirective)

    // Medium（enableTopBar=true）与 Compact 共用同一份 MobileNavTitleBar：
    // 显隐与内容完全由 page 的 ProvideMobileTitleBar + canGoBack 决定，globalActions 也保持一致。
    // Expanded（enableTopBar=false）保留桌面工作台形态，不显示 TopBar。
    val titleBarController = LocalMobileTitleBarController.current

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 主内容区
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets.systemBars.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End
                ),
                topBar = {
                    if (enableTopBar) {
                        MobileNavTitleBar(
                            canGoBack = navigationState.canGoBack,
                            onBack = { navigator.goBack() },
                            defaultTitle = (currentRoute as? NavDest)?.label ?: "Quant",
                            globalActions = {
                                DataUpdateStatusIndicator()
                                AppMenu(
                                    canDownloadApk = !isAndroidPlatform(),
                                    onDownloadApk = { downloadApk("${AppConfig.apiBaseUrl}/api/download/apk") },
                                )
                            },
                        )
                    }
                },
            ) { paddingValues ->
                val contentModifier = if (enableTopBar) {
                    titleBarController?.spec?.scrollBehavior?.let { scrollBehavior ->
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            .padding(paddingValues)
                    } ?: Modifier.fillMaxSize().padding(paddingValues)
                } else {
                    Modifier.fillMaxSize().padding(paddingValues)
                }
                Box(modifier = contentModifier) {
                    NavDisplay(
                        entries = navigationState.toEntries(entryProvider),
                        sceneStrategies = listOf(sceneStrategy),
                        onBack = { navigator.goBack() }
                    )
                    ToastHost(hostState = toastHostState, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }

            // AgentSidebar（宽屏常驻，窄屏折叠为 0dp 后由 Overlay 接管）
            AgentSidebar(
                isVisible = !config.isCompact,
                isExpanded = agentSidebarState.isExpanded,
                isProcessing = agentState.isProcessing,
                connectionStatus = agentState.connectionStatus,
                unreadCount = unreadCount,
                collapseToZero = shouldAutoCollapse,
                onToggleExpand = { agentSidebarViewModel.toggleExpand() },
                onWidthChange = null
            ) {
                AgentSidebarContent(
                    state = agentState,
                    selectedStock = sidebarSelectedStock,
                    onSendMessage = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.SendMessage(analysisType = it)) },
                    onUpdateInput = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.UpdateInput(it)) },
                    onApproveTool = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.ApproveTool(it)) },
                    onRejectTool = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.RejectTool(it)) },
                    onStopAgent = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.StopAgent) },
                    onResumeAgent = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.ResumeAgent) },
                    onConnect = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.Connect) },
                    onCollapse = if (!shouldAutoCollapse) null else {{ agentSidebarViewModel.setExpanded(false) }},
                    onNewSession = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.NewSession) }
                )
            }
        }

        // FAB + Overlay — 窄屏模式下 Sidebar 折叠后的浮动入口
        if (shouldAutoCollapse) {
            // 与 Compact 的 AgentFloatingCard 一致：浮层展开时拦截系统返回，先关浮层再回上一页
            PlatformBackHandler(enabled = overlayExpanded) {
                onOverlayExpandedChange(false)
            }
            AgentDesktopOverlay(
                isExpanded = overlayExpanded,
                isProcessing = agentState.isProcessing,
                connectionStatus = agentState.connectionStatus,
                unreadCount = unreadCount,
                onExpand = { onOverlayExpandedChange(true) },
                onCollapse = { onOverlayExpandedChange(false) },
                onConnect = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.Connect) }
            ) {
                AgentSidebarContent(
                    state = agentState,
                    selectedStock = sidebarSelectedStock,
                    onSendMessage = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.SendMessage(analysisType = it)) },
                    onUpdateInput = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.UpdateInput(it)) },
                    onApproveTool = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.ApproveTool(it)) },
                    onRejectTool = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.RejectTool(it)) },
                    onStopAgent = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.StopAgent) },
                    onResumeAgent = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.ResumeAgent) },
                    onConnect = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.Connect) },
                    onCollapse = { onOverlayExpandedChange(false) },
                    onNewSession = { agentViewModel.dispatch(org.shiroumi.quant_kmp.ui.agent.state.AgentContract.Action.NewSession) }
                )
            }
        }

        LargeScreenStatusSnackbar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
                .padding(bottom = 20.dp)
        )
    }
}

@Composable
private fun LargeScreenStatusSnackbar(
    modifier: Modifier = Modifier
) {
    val service = remember { DataUpdateService.getInstance() }
    val shouldShow by service.shouldShowIndicator.collectAsState()

    AnimatedVisibility(
        visible = shouldShow,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing))
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                DataUpdateStatusIndicator(centered = true)
            }
        }
    }
}
