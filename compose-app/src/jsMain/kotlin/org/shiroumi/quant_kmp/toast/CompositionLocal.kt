package org.shiroumi.quant_kmp.toast

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

val LocalToastHostState: ProvidableCompositionLocal<ToastHostState> =
    staticCompositionLocalOf {
        error("No ToastHostState provided. Please wrap your root component with CompositionLocalProvider.")
    }