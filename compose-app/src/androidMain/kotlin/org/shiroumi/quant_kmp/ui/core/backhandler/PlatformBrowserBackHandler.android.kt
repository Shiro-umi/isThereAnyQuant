package org.shiroumi.quant_kmp.ui.core.backhandler

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBrowserBackHandler(
    enabled: Boolean,
    backStackDepth: Int,
    onBack: () -> Boolean,
) = Unit
