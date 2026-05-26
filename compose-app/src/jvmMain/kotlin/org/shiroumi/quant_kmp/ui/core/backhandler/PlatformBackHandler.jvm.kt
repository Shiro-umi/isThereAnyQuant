package org.shiroumi.quant_kmp.ui.core.backhandler

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // JVM 桌面端无系统返回手势，为空实现
}
