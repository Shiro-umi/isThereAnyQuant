import SwiftUI

/// Quant iOS 壳工程入口。
///
/// 整个 UI 树由 Compose Multiplatform 承载：SwiftUI 仅提供 app 生命周期与窗口根，
/// 真正的界面来自 ComposeApp.framework 暴露的 `MainViewController()`。
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // Compose 自行处理安全区（WindowInsets.safeDrawing），
                // 这里让根视图铺满全屏，把刘海/Home 区交还给 Compose 布局。
                .ignoresSafeArea(.all)
        }
    }
}
