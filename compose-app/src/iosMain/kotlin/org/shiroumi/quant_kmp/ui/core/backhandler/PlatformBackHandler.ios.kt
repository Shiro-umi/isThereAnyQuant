package org.shiroumi.quant_kmp.ui.core.backhandler

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS 无 Android 式系统返回键，与 Web / JVM 同为空实现。
}
