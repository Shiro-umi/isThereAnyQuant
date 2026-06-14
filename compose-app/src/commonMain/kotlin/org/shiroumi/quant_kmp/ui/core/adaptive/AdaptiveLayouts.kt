package org.shiroumi.quant_kmp.ui.core.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.AdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig

/**
 * 自适应页面容器：限制内容最大宽度并居中，默认套用断点统一的页面内边距。
 *
 * 页面内边距取自 [AdaptiveLayoutConfig.pagePadding]，各页不再手搓 16/24/40dp 三档逻辑。
 * 自己管理内边距（例如沉浸式或分栏中缝）的页面把 [applyPagePadding] 设为 false。
 */
@Composable
fun AdaptivePageContainer(
    modifier: Modifier = Modifier,
    config: AdaptiveLayoutConfig = rememberAdaptiveLayoutConfig(),
    applyPagePadding: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = config.contentMaxWidth)
                .fillMaxHeight()
                .let { if (applyPagePadding) it.padding(config.pagePadding) else it }
        ) {
            content()
        }
    }
}

