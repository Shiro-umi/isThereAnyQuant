package org.shiroumi.quant_kmp.ui.theme

import androidx.compose.material3.ColorScheme

actual fun syncLoadingChrome(colorScheme: ColorScheme) {
    // iOS 不需要同步：首屏由 LaunchScreen / app 背景直接渲染，没有等价的 web 首屏 chrome 层。
}
