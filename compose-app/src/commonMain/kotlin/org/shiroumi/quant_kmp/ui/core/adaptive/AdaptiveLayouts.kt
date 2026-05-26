package org.shiroumi.quant_kmp.ui.core.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.AdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig

/**
 * 自适应页面容器
 * 根据屏幕尺寸限制内容最大宽度并居中
 */
@Composable
fun AdaptivePageContainer(
    modifier: Modifier = Modifier,
    config: AdaptiveLayoutConfig = rememberAdaptiveLayoutConfig(),
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
        ) {
            content()
        }
    }
}

