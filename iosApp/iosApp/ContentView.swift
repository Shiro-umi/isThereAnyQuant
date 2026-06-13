import SwiftUI
import ComposeApp

/// 把 Kotlin/Compose 暴露的 `MainViewController()` 桥接进 SwiftUI。
///
/// `MainViewController()` 来自 ComposeApp.framework，返回承载整个 Compose UI 树的
/// UIViewController。SwiftUI 通过 UIViewControllerRepresentable 将其托管为原生视图。
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // Compose 自管理状态，宿主侧无需在 SwiftUI 重组时回推任何内容。
    }
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}
