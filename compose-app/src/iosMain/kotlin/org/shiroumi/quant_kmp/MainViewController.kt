package org.shiroumi.quant_kmp

import androidx.compose.ui.window.ComposeUIViewController
import org.shiroumi.quant_kmp.ui.auth.AuthGate
import org.shiroumi.quant_kmp.ui.theme.AppTheme
import platform.UIKit.UIViewController

/**
 * iOS Compose 根入口。
 *
 * 由 iosApp/ 下的 Xcode 壳工程（SwiftUI）调用，返回承载整个 Compose UI 树的 UIViewController。
 * 与 Android MainActivity.setContent、Web main() 的 ComposeViewport 对齐：
 * AppTheme -> AuthGate（登录态网关）-> Navigation 根布局。
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    AppTheme {
        AuthGate()
    }
}
