package org.shiroumi.quant_kmp.ui.core.backhandler

import androidx.compose.runtime.Composable

/**
 * 浏览器历史返回拦截器。
 *
 * 仅 Web 平台实现；Android / JVM 为 no-op。
 */
@Composable
expect fun PlatformBrowserBackHandler(
    enabled: Boolean = true,
    backStackDepth: Int = 0,
    onBack: () -> Boolean,
)
