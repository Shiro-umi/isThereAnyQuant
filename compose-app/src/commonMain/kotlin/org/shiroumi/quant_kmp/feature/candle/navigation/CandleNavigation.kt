package org.shiroumi.quant_kmp.feature.candle.navigation

import androidx.compose.runtime.Composable
import org.shiroumi.quant_kmp.NavDest
import org.shiroumi.quant_kmp.feature.candle.presentation.CandleScreen
import org.shiroumi.quant_kmp.feature.candle.presentation.CandleViewModel
import org.shiroumi.quant_kmp.ui.components.ToastHostState

/**
 * 蜡烛图分析页面导航入口
 *
 * @param toastHostState Toast状态
 */
@Composable
fun CandleRoute(
    toastHostState: ToastHostState,
    viewModel: CandleViewModel? = null,
    route: NavDest.Candle = NavDest.Candle(),
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    if (viewModel != null) {
        CandleScreen(
            toastHostState = toastHostState,
            viewModel = viewModel,
            route = route,
            onNavigateToDetail = onNavigateToDetail,
            onNavigateBack = onNavigateBack,
        )
    } else {
        CandleScreen(
            toastHostState = toastHostState,
            route = route,
            onNavigateToDetail = onNavigateToDetail,
            onNavigateBack = onNavigateBack,
        )
    }
}
