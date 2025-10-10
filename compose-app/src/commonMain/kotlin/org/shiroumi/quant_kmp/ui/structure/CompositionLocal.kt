package org.shiroumi.quant_kmp.ui.structure

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

val LocalNavTab: ProvidableCompositionLocal<NavTab.Companion> =
    staticCompositionLocalOf {
        error("No ToastHostState provided. Please wrap your root component with CompositionLocalProvider.")
    }

sealed class NavTab {
    data object QuantTab : NavTab()
    data object TaskTab : NavTab()


    companion object {
        var currTab by mutableStateOf<NavTab>(QuantTab)
    }
}