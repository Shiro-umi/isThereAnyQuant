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
                // 窗口根铺主题深底：ContentView 的兜底背景之外再加一层，
                // 防止 WindowGroup 根在任何阶段透出系统白底（启动首帧 / 转场掉帧）。
                .background(Color(red: 0x1A / 255.0, green: 0x11 / 255.0, blue: 0x0F / 255.0).ignoresSafeArea(.all))
        }
    }
}
