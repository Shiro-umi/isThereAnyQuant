import SwiftUI
import ComposeApp

/// 主题深色背景（暖棕红 dark = 0xFF1A110F），与 Compose 端 colorScheme.background 同值。
///
/// 用途：作为 SwiftUI 宿主层与 UIWindow 的兜底底色。Compose 渲染层在导航转场、首帧、
/// Metal 合成短暂未覆盖时会透出宿主层；宿主层不铺底则透出 UIWindow 默认白底，表现为
/// 「页面切换瞬间闪白」。在 Compose 之下铺同色深底，使任何露出瞬间都是主题色而非白色。
private extension Color {
    static let quantBackground = Color(
        red: 0x1A / 255.0,
        green: 0x11 / 255.0,
        blue: 0x0F / 255.0
    )
}

/// 把 Kotlin/Compose 暴露的 `MainViewController()` 桥接进 SwiftUI。
///
/// `MainViewController()` 来自 ComposeApp.framework，返回承载整个 Compose UI 树的
/// UIViewController。SwiftUI 通过 UIViewControllerRepresentable 将其托管为原生视图。
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController()
        // 宿主侧再兜一层：UIViewController 根 view 铺主题深色，确保 Compose 渲染层
        // 任何露出/掉帧瞬间不暴露系统白底（与 SwiftUI 根 background 互补）。
        controller.view.backgroundColor = UIColor(
            red: 0x1A / 255.0,
            green: 0x11 / 255.0,
            blue: 0x0F / 255.0,
            alpha: 1.0
        )
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // Compose 自管理状态，宿主侧无需在 SwiftUI 重组时回推任何内容。
    }
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
            // 宿主根铺主题深底，覆盖刘海/Home 区与转场露出区，杜绝透白。
            .background(Color.quantBackground.ignoresSafeArea(.all))
    }
}
