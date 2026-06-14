package org.shiroumi.quant_kmp

import androidx.compose.ui.ExperimentalComposeUiApi
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
 *
 * `opaque = true` 本就是 ComposeUIViewControllerConfiguration 的默认值，这里显式固化以防上游
 * 默认变更，并声明意图：Compose 渲染层保持不透明，转场期间不透出 SwiftUI 宿主层。
 * 真正消除「背景先透明再闪现」的是 AppTheme 内部全屏 Surface 立即填充主题背景色——
 * 不透明渲染层（opaque）+ 立即铺底（Surface）二者互补，既不闪白也不闪透。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController(): UIViewController = ComposeUIViewController(
    configure = { opaque = true },
) {
    AppTheme {
        AuthGate()
    }
}
