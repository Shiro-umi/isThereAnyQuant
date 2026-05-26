package org.shiroumi.quant_kmp.feature.agent.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.shiroumi.quant_kmp.NavDest
import org.shiroumi.quant_kmp.feature.agent.contract.AgentAnalysisContract
import org.shiroumi.quant_kmp.feature.agent.presentation.components.AgentAnalysisDetailPanel
import org.shiroumi.quant_kmp.feature.agent.presentation.components.AgentAnalysisListPanel
import org.shiroumi.quant_kmp.platform.copyToClipboard
import org.shiroumi.quant_kmp.ui.components.ToastHostState
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.navigation.MobileTitleMotion
import org.shiroumi.quant_kmp.ui.markdown.QuantBlock
import org.shiroumi.quant_kmp.ui.markdown.QuantMarkdownBlockParser

private val CompactTitleRevealThreshold = 84.dp

/**
 * Agent 分析结果 pane entry。
 * list/detail 由顶层 Navigation 3 scene strategy 决定是否并列。
 */
@Composable
fun AgentAnalysisScreen(
    viewModel: AgentAnalysisViewModel,
    toastHostState: ToastHostState,
    route: NavDest.AgentResults = NavDest.AgentResults(),
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val isListPane = route.resultId == null
    val sceneScope = LocalListDetailSceneScope.current
    val showCompactBack = !isListPane && sceneScope == null
    val detailScrollState = rememberScrollState()
    val titleRevealThresholdPx = with(LocalDensity.current) { CompactTitleRevealThreshold.roundToPx() }
    val compactTopBarTitle by remember(showCompactBack, state.selectedResult?.contentMd, titleRevealThresholdPx) {
        derivedStateOf {
            if (!showCompactBack || detailScrollState.value < titleRevealThresholdPx) {
                ""
            } else {
                state.selectedResult?.stockTitleForTopBar().orEmpty()
            }
        }
    }

    LaunchedEffect(route.resultId, state.results) {
        val resultId = route.resultId ?: return@LaunchedEffect
        if (state.selectedResult?.id != resultId) {
            state.results.firstOrNull { it.id == resultId }?.let {
                viewModel.dispatch(AgentAnalysisContract.Action.SelectResult(it))
            }
        }
    }

    // 标题栏：单栏且有选中项时显示返回箭头
    org.shiroumi.quant_kmp.ui.navigation.ProvideMobileTitleBar(
        title = if (isListPane) "分析" else compactTopBarTitle,
        onBack = if (showCompactBack) onNavigateBack else null,
        titleMotion = MobileTitleMotion.StaggeredHorizontal,
    )

    // 收集副作用
    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is AgentAnalysisContract.Effect.ShowToast -> toastHostState.showToast(effect.message)
                is AgentAnalysisContract.Effect.CopyShareUrl -> {
                    val copied = copyToClipboard(effect.url)
                    toastHostState.showToast(
                        if (copied) "分享链接已复制：${effect.url}" else "链接：${effect.url}"
                    )
                }
            }
        }
    }

    // 错误提示（SnackBar）
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dispatch(AgentAnalysisContract.Action.ClearError)
        }
    }

    val layoutConfig = rememberAdaptiveLayoutConfig()
    // 与 CandleScreen 对齐：Medium 及以上按 24dp 外缘 + 24dp 中缝排版；
    // 并列形态下 list/detail 各占一半中缝，单栏形态保留全 24dp 内边距。
    // Compact 形态下 list pane 走扁平 ListItem（内部已含 16dp horizontal padding），外层不再叠加水平边距；
    // detail pane 仍保留 16dp 水平安全边距，避免 Markdown 内容贴屏幕边缘。
    val paneModifier = when {
        layoutConfig.isCompact -> if (isListPane) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxSize().padding(horizontal = 16.dp)
        }
        sceneScope != null -> if (isListPane) {
            Modifier.fillMaxSize().padding(start = 24.dp, end = 12.dp, top = 24.dp, bottom = 24.dp)
        } else {
            Modifier.fillMaxSize().padding(start = 12.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)
        }
        else -> Modifier.fillMaxSize().padding(24.dp)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isListPane) {
                AgentAnalysisListPanel(
                    results = state.results,
                    selectedId = state.selectedResult?.id,
                    isLoading = state.isLoading,
                    errorMessage = state.errorMessage,
                    onItemClick = {
                        viewModel.dispatch(AgentAnalysisContract.Action.SelectResult(it))
                        onNavigateToDetail(it.id)
                    },
                    onDeleteClick = { viewModel.dispatch(AgentAnalysisContract.Action.DeleteResult(it)) },
                    onRetry = { viewModel.dispatch(AgentAnalysisContract.Action.LoadResults) },
                    modifier = paneModifier
                )
            } else {
                state.selectedResult?.let { result ->
                    AgentAnalysisDetailPanel(
                        result = result,
                        scrollState = detailScrollState,
                        shareStats = state.selectedShareStats,
                        isSharing = state.isSharing,
                        onShareClick = { viewModel.dispatch(AgentAnalysisContract.Action.CreateShare(it.id)) },
                        modifier = paneModifier
                    )
                } ?: EmptyDetailHint()
            }
        }
    }
}

private fun model.agent.AgentAnalysisResultDto.stockTitleForTopBar(): String {
    return firstReportHeaderStockName(contentMd)
        ?: title?.takeIf { it.isNotBlank() }
        ?: tsCode.orEmpty()
}

private fun firstReportHeaderStockName(content: String): String? {
    val fenceRegex = Regex("""```(quant-header)\s*([\s\S]*?)```""")
    return fenceRegex.find(content)
        ?.let { match -> QuantMarkdownBlockParser.parse(match.groupValues[1], match.groupValues[2]) }
        ?.let { block -> (block as? QuantBlock.Header)?.spec?.stockName }
        ?.takeIf { it.isNotBlank() }
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
            text = "选择分析结果查看详情",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
