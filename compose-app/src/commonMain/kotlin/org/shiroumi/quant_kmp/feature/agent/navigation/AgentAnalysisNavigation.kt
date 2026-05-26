package org.shiroumi.quant_kmp.feature.agent.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import org.shiroumi.quant_kmp.NavDest
import org.shiroumi.quant_kmp.feature.agent.data.repository.AgentAnalysisRepository
import org.shiroumi.quant_kmp.feature.agent.presentation.AgentAnalysisScreen
import org.shiroumi.quant_kmp.feature.agent.presentation.AgentAnalysisViewModel
import org.shiroumi.quant_kmp.di.HttpClientProvider
import org.shiroumi.quant_kmp.ui.components.ToastHostState

/**
 * Agent 分析结果页面导航入口
 *
 * @param toastHostState Toast状态
 */
@Composable
fun AgentAnalysisRoute(
    toastHostState: ToastHostState,
    route: NavDest.AgentResults = NavDest.AgentResults(),
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = viewModel {
        val repository = AgentAnalysisRepository(
            httpClient = HttpClientProvider.apiClient
        )
        AgentAnalysisViewModel(repository)
    }

    AgentAnalysisScreen(
        viewModel = viewModel,
        toastHostState = toastHostState,
        route = route,
        onNavigateToDetail = onNavigateToDetail,
        onNavigateBack = onNavigateBack,
    )
}
