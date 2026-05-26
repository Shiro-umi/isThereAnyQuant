package org.shiroumi.quant_kmp.ui.core.backhandler

import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
