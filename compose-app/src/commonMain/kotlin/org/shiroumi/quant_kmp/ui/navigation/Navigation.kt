package org.shiroumi.quant_kmp.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import org.shiroumi.config.AppConfig
import org.shiroumi.quant_kmp.MultiPlatform
import org.shiroumi.quant_kmp.NavDest
import org.shiroumi.quant_kmp.di.HttpClientProvider
import org.shiroumi.quant_kmp.feature.agent.navigation.AgentAnalysisRoute
import org.shiroumi.quant_kmp.feature.auth.AuthContract
import org.shiroumi.quant_kmp.feature.candle.data.repository.CandleRepositoryImpl
import org.shiroumi.quant_kmp.feature.candle.navigation.CandleRoute
import org.shiroumi.quant_kmp.feature.candle.presentation.CandleViewModel
import org.shiroumi.quant_kmp.service.GlobalWebSocketClient
import org.shiroumi.quant_kmp.service.StockContextProvider
import org.shiroumi.quant_kmp.ui.agent.viewmodel.AgentViewModel
import org.shiroumi.quant_kmp.ui.components.ToastHostState
import org.shiroumi.quant_kmp.ui.components.rememberToastHostState
import org.shiroumi.quant_kmp.ui.core.lifecycle.PlatformLifecycleEffect
import org.shiroumi.quant_kmp.ui.core.viewmodel.LocalAuthViewModel

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Navigation() = MultiPlatform {
    val toastHostState = rememberToastHostState()
    val authViewModel = LocalAuthViewModel.current
    val authState by authViewModel.state.collectAsState()

    // 创建共享的 AgentViewModel（全局生命周期）
    val agentViewModel = viewModel { AgentViewModel() }
    val candleViewModel = viewModel {
        CandleViewModel(
            CandleRepositoryImpl(
                httpClient = HttpClientProvider.apiClient,
                baseUrl = AppConfig.apiBaseUrl
            )
        )
    }
    val agentState by agentViewModel.state.collectAsState()
    val selectedStock by StockContextProvider.selectedStock.collectAsState()

    PlatformLifecycleEffect(
        onResume = { GlobalWebSocketClient.connect() },
        onPause = { GlobalWebSocketClient.disconnect() }
    )

    LaunchedEffect(Unit) {
        GlobalWebSocketClient.connect()
    }

    val topLevelRoutes = remember {
        setOf(
            NavDest.Candle(),
            NavDest.Sentiment,
            NavDest.PositionTracking,
            NavDest.AgentResults(),
            NavDest.Settings,
        )
    }
    val navigationState = rememberNavigationState(
        startRoute = NavDest.Candle(),
        topLevelRoutes = topLevelRoutes
    )
    val navigator = remember { Navigator(navigationState) }

    // 监听路由变化：离开行情页时停止股票列表上下文推送，但保留上次选中的股票以便返回时恢复
    val currentRoute = navigationState.topLevelRoute
    LaunchedEffect(currentRoute) {
        if (currentRoute !is NavDest.Candle) {
            GlobalWebSocketClient.setStockListContext(emptyList())
        }
    }

    val sidebarSelectedStock = if (currentRoute is NavDest.Candle) selectedStock else null

    val entryProvider: (NavKey) -> NavEntry<NavKey> = { key ->
        when (key) {
            is NavDest.Candle -> NavEntry(
                key = key,
                metadata = if (key.code == null) {
                    ListDetailSceneStrategy.listPane()
                } else {
                    ListDetailSceneStrategy.detailPane()
                }
            ) {
                OpaquePage {
                    CandleRoute(
                        toastHostState = toastHostState,
                        viewModel = candleViewModel,
                        route = key,
                        onNavigateToDetail = { code -> navigator.navigate(NavDest.Candle(code)) },
                        onNavigateBack = { navigator.goBack() }
                    )
                }
            }
            is NavDest.Sentiment -> NavEntry(key) {
                OpaquePage {
                    org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.SentimentScreen()
                }
            }
            is NavDest.PositionTracking -> NavEntry(key) {
                OpaquePage {
                    org.shiroumi.quant_kmp.feature.strategytracking.presentation.StrategyPositionTrackingScreen()
                }
            }
            is NavDest.AgentResults -> NavEntry(
                key = key,
                metadata = if (key.resultId == null) {
                    ListDetailSceneStrategy.listPane()
                } else {
                    ListDetailSceneStrategy.detailPane()
                }
            ) {
                OpaquePage {
                    AgentAnalysisRoute(
                        toastHostState = toastHostState,
                        route = key,
                        onNavigateToDetail = { resultId -> navigator.navigate(NavDest.AgentResults(resultId)) },
                        onNavigateBack = { navigator.goBack() }
                    )
                }
            }
            is NavDest.Settings -> NavEntry(key) {
                OpaquePage {
                    org.shiroumi.quant_kmp.feature.settings.SettingsRoute(
                        user = authState.user,
                        onLogout = { authViewModel.dispatch(AuthContract.Action.Logout) }
                    )
                }
            }
            else -> NavEntry(key) { error("未处理的 NavKey 类型: ${key::class.simpleName}，请在 entryProvider 中添加对应分支") }
        }
    }

    CompositionLocalProvider(LocalNavigationState provides navigationState) {
        AppNavigationSuite(
            navigationState = navigationState,
            navigator = navigator,
            entryProvider = entryProvider,
            toastHostState = toastHostState,
            agentViewModel = agentViewModel,
            agentState = agentState,
            sidebarSelectedStock = sidebarSelectedStock
        )
    }
}

/**
 * 每个 NavEntry 内容的不透明铺底。
 *
 * NavDisplay 切换顶级页时新页面 fadeIn、旧页面 fadeOut，淡入期间两者叠加可见。
 * 页面自身（情绪页、设置页）根容器不铺背景，且 AppTheme 的根 Surface 在 NavDisplay
 * 外层、是两个转场页面的共同底，挡不住页面之间互相透出。这里在转场的最小渲染单元
 * （NavEntry 内容）统一铺一层不透明主题背景，使任意页面在淡入期间底层恒为主题色，
 * 杜绝漏出上一页。一处治本，覆盖全部目的地及未来新增页面。
 */
@Composable
private fun OpaquePage(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        content = content,
    )
}

