package org.shiroumi.quant_kmp.feature.candle.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import org.shiroumi.quant_kmp.NavDest
import org.shiroumi.quant_kmp.feature.candle.contract.CandleContract
import org.shiroumi.quant_kmp.feature.candle.data.repository.CandleRepositoryImpl
import org.shiroumi.quant_kmp.feature.candle.presentation.components.CandleChartSection
import org.shiroumi.quant_kmp.feature.candle.presentation.components.StockListPanel
import org.shiroumi.quant_kmp.service.ConnectionState
import org.shiroumi.quant_kmp.di.HttpClientProvider
import org.shiroumi.quant_kmp.ui.agent.theme.AgentTheme
import org.shiroumi.quant_kmp.ui.agent.theme.LocalAgentShapes
import org.shiroumi.quant_kmp.ui.agent.theme.LocalAgentSpacing
import org.shiroumi.quant_kmp.ui.components.ToastHostState
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.navigation.LocalNavigationState
import org.shiroumi.quant_kmp.ui.navigation.ProvideMobileTitleBar
import org.shiroumi.config.AppConfig

/**
 * 蜡烛图分析页面 pane entry。
 * list/detail 由顶层 Navigation 3 scene strategy 决定是否并列。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CandleScreen(
    toastHostState: ToastHostState,
    route: NavDest.Candle = NavDest.Candle(),
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    viewModel: CandleViewModel = viewModel {
        val repository = CandleRepositoryImpl(
            httpClient = HttpClientProvider.apiClient,
            baseUrl = AppConfig.apiBaseUrl
        )
        CandleViewModel(repository)
    }
) {
    CompositionLocalProvider(
        LocalAgentSpacing provides AgentTheme.Spacing,
        LocalAgentShapes provides AgentTheme.Shapes
    ) {
        val state by viewModel.state.collectAsState()
        val isListPane = route.code == null
        val sceneScope = LocalListDetailSceneScope.current
        val showCompactBack = !isListPane && sceneScope == null
        val layoutConfig = rememberAdaptiveLayoutConfig()
        val useCompactVisuals = layoutConfig.isCompact
        val useMediumSheetList = layoutConfig.isMedium
        var showStockListSheet by remember { mutableStateOf(false) }
        // 页边距：Medium 及以上按旧版 24dp 外缘 + 24dp 中缝还原；Compact 保持沉浸式
        val pageModifier = if (layoutConfig.isCompact) {
            Modifier.fillMaxSize()
        } else if (sceneScope != null) {
            // 并列形态：list/detail 各占一半中缝
            if (isListPane) {
                Modifier.fillMaxSize().padding(start = 24.dp, end = 12.dp, top = 24.dp, bottom = 24.dp)
            } else {
                Modifier.fillMaxSize().padding(start = 12.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)
            }
        } else {
            Modifier.fillMaxSize().padding(24.dp)
        }

        LaunchedEffect(route.code, state.stocks) {
            val code = route.code ?: return@LaunchedEffect
            if (state.selectedStock?.code != code) {
                state.stocks.firstOrNull { it.code == code }?.let {
                    viewModel.dispatch(CandleContract.Action.SelectStock(it))
                }
            }
        }

        // 并列形态下 list pane 是栈顶但 detail entry 还没入栈时，自动 push 当前选中股票的 detail，
        // 让 ListDetailSceneStrategy 能在右侧渲染 K 线，避免首次进入大屏时右半区空白。
        val navigationState = LocalNavigationState.current
        LaunchedEffect(isListPane, sceneScope, useMediumSheetList, state.selectedStock?.code) {
            if (!isListPane || sceneScope == null || useMediumSheetList) return@LaunchedEffect
            val code = state.selectedStock?.code ?: return@LaunchedEffect
            val candleStack = navigationState
                ?.backStacks
                ?.entries
                ?.firstOrNull { it.key is NavDest.Candle }
                ?.value
            val alreadyHasDetail = candleStack?.any { it is NavDest.Candle && it.code == code } == true
            if (!alreadyHasDetail) {
                onNavigateToDetail(code)
            }
        }

        DisposableEffect(viewModel) {
            viewModel.onScreenEnter()
            onDispose { viewModel.onScreenLeave() }
        }

        // 收集副作用
        LaunchedEffect(Unit) {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is CandleContract.Effect.ShowToast -> toastHostState.showToast(effect.message)
                    is CandleContract.Effect.NavigateToStockDetail -> { /* 已由 scaffold 处理 */ }
                }
            }
        }

        ProvideMobileTitleBar(
            title = "行情",
            onBack = if (showCompactBack) onNavigateBack else null,
        )

        if (isListPane && !useMediumSheetList) {
            StockListPanel(
                stocks = state.filteredStocks,
                selectedStock = state.selectedStock,
                searchQuery = state.searchQuery,
                strategySelectionCodes = state.strategySelectionCodes,
                isStrategySelectionReady = state.isStrategySelectionReady,
                sentimentHistory = state.sentimentHistory,
                isLoading = state.isLoadingStocks,
                hasMore = state.hasMoreStocks,
                isLoadingMore = state.isLoadingMore,
                onStockSelected = {
                    viewModel.dispatch(CandleContract.Action.SelectStock(it))
                    onNavigateToDetail(it.code)
                },
                onSearchQueryChange = { viewModel.dispatch(CandleContract.Action.UpdateSearchQuery(it)) },
                onLoadMore = { viewModel.dispatch(CandleContract.Action.LoadMoreStocks) },
                onVisibleStocksChanged = { viewModel.dispatch(CandleContract.Action.UpdateVisibleStocks(it)) },
                constrainWidth = !useCompactVisuals,
                isCompact = useCompactVisuals,
                modifier = pageModifier
            )
        } else {
            // 断线/重连期间用专门文案替代普通"加载中"，避免命令被 isRestorableStateCommand
            // 静默推迟时 UI 长时间无声 loading。
            val loadingMessage = when (state.connectionState) {
                ConnectionState.CONNECTING -> "正在连接服务器..."
                ConnectionState.RECONNECTING -> "网络重连中…"
                ConnectionState.DISCONNECTED -> "未连接"
                ConnectionState.CONNECTED -> null
            }
            CandleChartSection(
                stockInfo = state.selectedStock,
                chartData = state.chartData,
                marketStatus = state.marketStatus,
                selectedPeriod = state.selectedPeriod,
                isLoading = state.isLoadingCandle,
                showVolume = state.showVolume,
                showRsi = state.showRsi,
                showMacd = state.showMacd,
                showEma = state.showEma,
                showMa = state.showMa,
                onPeriodSelected = { viewModel.dispatch(CandleContract.Action.SelectPeriod(it)) },
                onToggleVolume = { viewModel.dispatch(CandleContract.Action.ToggleVolume(it)) },
                onToggleRsi = { viewModel.dispatch(CandleContract.Action.ToggleRsi(it)) },
                onToggleMacd = { viewModel.dispatch(CandleContract.Action.ToggleMacd(it)) },
                onToggleEma = { viewModel.dispatch(CandleContract.Action.ToggleEma(it)) },
                onToggleMa = { viewModel.dispatch(CandleContract.Action.ToggleMa(it)) },
                onShowStockList = if (useMediumSheetList) {
                    { showStockListSheet = true }
                } else null,
                isCompact = useCompactVisuals,
                loadingMessage = loadingMessage,
                errorMessage = state.candleError,
                modifier = pageModifier
            )
        }

        if (useMediumSheetList && showStockListSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val scope = rememberCoroutineScope()
            val dismissSheet: () -> Unit = {
                scope.launch {
                    sheetState.hide()
                    showStockListSheet = false
                }
            }
            ModalBottomSheet(
                onDismissRequest = dismissSheet,
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ) {
                StockListPanel(
                    stocks = state.filteredStocks,
                    selectedStock = state.selectedStock,
                    searchQuery = state.searchQuery,
                    strategySelectionCodes = state.strategySelectionCodes,
                    isStrategySelectionReady = state.isStrategySelectionReady,
                    sentimentHistory = state.sentimentHistory,
                    isLoading = state.isLoadingStocks,
                    hasMore = state.hasMoreStocks,
                    isLoadingMore = state.isLoadingMore,
                    onStockSelected = {
                        viewModel.dispatch(CandleContract.Action.SelectStock(it))
                        dismissSheet()
                    },
                    onSearchQueryChange = { viewModel.dispatch(CandleContract.Action.UpdateSearchQuery(it)) },
                    onLoadMore = { viewModel.dispatch(CandleContract.Action.LoadMoreStocks) },
                    onVisibleStocksChanged = { viewModel.dispatch(CandleContract.Action.UpdateVisibleStocks(it)) },
                    constrainWidth = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 420.dp, max = 720.dp)
                        .padding(bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyDetailHint() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "请选择股票查看 K 线",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
