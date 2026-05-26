# Quant 自适应布局迁移计划 — 执行状态总结

> 原始文档：`docs/adaptive-layout-migration-plan-c.html`（方案 C：canonical scaffold）
> 最后更新：2026-05-13

---

## 一、原始文档概述

迁移计划共 **9 个阶段**（阶段 0~8），目标是将 Quant 的 Compose Multiplatform 前端从手写自适应布局迁移到 Material 3 Adaptive canonical scaffold：

| 阶段 | 内容 |
|------|------|
| 0 | 依赖接入（`material3.adaptive` + `adaptive-layout` + `adaptive-navigation`）+ 烟雾验证 |
| 1 | `WindowSizeClass` 重写为基于 `currentWindowAdaptiveInfo()` |
| 2 | 导航 Shell 重构为 `NavigationSuiteScaffold` |
| 3 | Candle 页面迁移到 `ListDetailPaneScaffold` |
| 4 | AgentAnalysis 页面迁移到 `ListDetailPaneScaffold` |
| 5 | Agent 独立聊天页方案已撤销；保留 Sidebar/FloatingCard 快捷入口 |
| 6 | Sentiment 页面迁移到 `LazyVerticalGrid.Adaptive` |
| 7 | StrategyTracking / Login / Profile 断点校准 |
| 8 | 旧 API 清理 + 文档同步 |

**原始文档版本预期**：`material3.adaptive:1.3.0-beta01`（5 档断点 + `adaptive-navigation3`）

---

## 二、已完成的

### 依赖与基线
- [x] `gradle/libs.versions.toml` 引入 adaptive / adaptive-layout / adaptive-navigation
- [x] `compose-app/build.gradle.kts` 引入 bundle + module 级 `ExperimentalMaterial3AdaptiveApi` opt-in
- [x] 新建 `ui/core/adaptive/m3/` 目录
- [x] **版本升级**：`composeMultiplatform 1.10.3 → 1.11.0-rc01`，`material3Adaptive 1.2.0-alpha02 → 1.3.0-alpha07`

### WindowSizeClass 重写（阶段 1）
- [x] 新增 `AdaptiveInfo.kt`，`AdaptiveLayoutConfig` 基于 `currentWindowAdaptiveInfo()` V2
- [x] **5 档断点已落地**：Compact / Medium / Expanded / Large / XLarge（阈值：600/840/1200/1600）
- [x] `isLarge`、`isXLarge`、`contentMaxWidth`（1400.dp / 1600.dp）、`listDetailRatio` 已暴露
- [x] 旧文件 `WindowSizeClass.kt` / `.js.kt` / `.android.kt` 已删除
- [x] `calculateGridColumns` 已删除

### 导航 Shell（阶段 2）
- [x] 新增 `AppNavigationSuite.kt`，使用 `NavigationSuiteScaffold`
- [x] `Navigation.kt` 入口改调新 shell
- [x] 旧 `CompactNavigationLayout` / `MediumNavigationLayout` / `RailNavigationLayout` 已删除
- [x] 导航目的地集合已迁移到 `AppNavigationSuite.kt` 统一维护

### Candle 页面（阶段 3）
- [x] `CandleScreen.kt` 改为 `ListDetailPaneScaffold` + `rememberListDetailPaneScaffoldNavigator<String>()`
- [x] `StockListBottomSheet` 已删除
- [x] 顶层 `NavDisplay` 已接入 `rememberListDetailSceneStrategy()`
- [x] Candle list/detail 已拆成 Navigation 3 entry，pane 状态进入顶层 back stack
- [x] WebSocket 订阅由 ViewModel `SelectStock` 驱动，navigator 只管理 UI 姿态

### AgentAnalysis 页面（阶段 4）
- [x] `AgentAnalysisScreen.kt` 改为 Navigation 3 list/detail scene entry
- [x] Contract 删除 `isDetailOpen` / `CloseDetail`
- [x] ViewModel 保留 `SelectResult`（触发数据加载），不再控制详情可见性

### Agent 独立聊天页（阶段 5）
- [x] 独立聊天页方案已撤销
- [x] 旧独立 Agent 聊天顶层入口、导航项和页面专属组件已删除
- [x] Agent 对话能力保留在 `AgentSidebarContent` / `AgentFloatingCard`

### Sentiment 页面（阶段 6）
- [x] `SentimentContent` 改为 `GridCells.Adaptive(minSize = 320.dp)`
- [x] 手动 `columns` 计算和特殊 Row 嵌套已移除
- [x] `SentimentHeader` / `FeaturedSentimentCard` 使用 `GridItemSpan(maxLineSpan)`

### 校准（阶段 7）
- [x] `LoginScreen`：`isLarge || isXLarge` 才展示 BrandPanel 左右分栏，其余走单栏
- [x] `StrategyPositionTrackingScreen` / `TrackingTimelineLayout` 断点映射已更新（Expanded 含 Large/XLarge）
- [x] `Profile`：迁回 `AdaptivePageContainer`，使用全局 5 档 `contentMaxWidth` 映射，移除固定 480.dp 宽度卡片方案

### 清理（阶段 8）
- [x] 旧 `ListDetailLayout` 等已删除
- [x] 全量 import 迁移到 `m3` 包
- [x] `SmokeAdaptive` 已删除
- [x] `references/adaptive-layout-architecture.md` 已更新

### 编译验证
- [x] `./gradlew :compose-app:compileCommonMainKotlinMetadata` BUILD SUCCESSFUL
- [x] `./gradlew :compose-app:compileKotlinWasmJs` BUILD SUCCESSFUL

---

## 三、未完成的 + 原因

### 1. `adaptive-navigation3` scene strategy 已接入

**已完成**：
- `libs.versions.toml` / `compose-app/build.gradle.kts` 已补入 `org.jetbrains.compose.material3.adaptive:adaptive-navigation3`
- 顶层 `NavDisplay` 已配置 `rememberListDetailSceneStrategy()`
- `NavDest.Candle(code)`、`NavDest.AgentResults(resultId)` 现同时承载 list/detail 两类 entry
- Candle / AgentAnalysis 的 list/detail pane 已通过 metadata 交给 `ListDetailSceneStrategy` 组织

**边界说明**：
- 当前 Compose Multiplatform `1.3.0-alpha07` 依赖下，项目仍无法直接使用 `NavigableListDetailPaneScaffold` / `NavigableSupportingPaneScaffold`
- 本轮采用官方 scene strategy 路线完成等价目标，而不是继续停留在 pane-local navigator
- pane 返回现在进入项目当前 Navigation 3 顶级 back stack，可复用统一 `Navigator.goBack()`

### 2. Agent 独立聊天页方案已撤销

**当前状态**：
- 独立 Agent 聊天页不再作为顶级导航入口
- 页面专属 `SupportingPaneScaffold`、session/detail pane 和 status bar 组件已清理
- 当前全局在线聊天入口保留 `AgentSidebarContent` / `AgentFloatingCard`

### 3. 浏览器历史导航（Browser Back 映射到 pane 返回）

**原始文档风险表提及**：
- "Wasm predictive back 不可用，返回手势缺失"
- 缓解：保留按钮 + 浏览器 BFCache + nav3 back stack 兜底

**实际状态**：
- Compact 模式下从详情返回列表仍保留 UI 返回按钮
- Web 端已补 `PlatformBrowserBackHandler`
- `NavigationState.browserBackDepth` 把顶级路由切换、当前栈深度、已打开的临时弹层（supporting pane）合并成浏览器历史深度
- 新增 `TransientBackConsumer` + `RegisterTransientBack(isOpen, onClose)`：supporting pane 等弹层把"打开"状态注册到 `NavigationState`，`Navigator.goBack()` 优先 LIFO 关闭弹层，再回退栈
- 当前无独立 Agent 聊天顶级路由；浏览器/系统 Back 主要覆盖顶级路由栈与其它 `TransientBackConsumer`

**边界说明**：
- 当前实现仍是项目内桥接，不是官方 `navigation3-browser`
- 目标是保证 Back 与当前 Nav3 栈深度 + pane 状态一致；浏览器 Forward 的完整可恢复序列仍不在本轮范围
- pane 打开/关闭通过组件本地状态驱动，宽屏 `directive` 让 supporting pane 常驻时不会被错误计入返回深度

---

## 四、替代方案与参考链接

### 4.1 `adaptive-navigation3` 场景化集成现状

**当前状态**：`adaptive-navigation3` 依赖与顶层 `NavDisplay` scene strategy 均已接入。

**优点**：
- 现有业务 ViewModel 结构无需重写
- pane 切换动画、单栏/双栏自动管理仍由 M3 库处理

**缺点**：
- 目前尚未把 detail entry 映射成 URL 可分享链接
- browser history 仍是短期 popstate 桥接，不是完整历史序列建模

**后续接入路径**：
1. 如未来重新需要独立 Agent 工作区，再重新评估 supporting-pane scene
2. 如需要可分享 URL，再补 route ↔ URL 映射
3. 如需要完整浏览器历史序列，再评估 `navigation3-browser` 或后续官方能力

**参考链接**：
- [Compose Multiplatform Navigation 3 官方文档](https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html)
- [Android Navigation 3 Overview](https://developer.android.com/guide/navigation/navigation-jetpack-3-overview)

### 4.2 Agent 独立聊天页撤销结果

**当前实现**：无独立 Agent 聊天顶级路由；Agent 对话通过 Sidebar/FloatingCard 进入。

**已完成**：
- 删除独立路由和导航项
- 删除独立页专属布局、状态栏、输入区、欢迎页和 supporting pane 组件
- 保留 Sidebar/FloatingCard 的真实业务入口

**后续若继续演进**：
1. 若产品需要跨 session 切换，再补后端 session 列表与恢复协议
2. 若需要 route 级三 pane，再评估 `SupportingPaneSceneStrategy`

**参考链接**：
- [SupportingPaneScaffold 官方文档](https://developer.android.com/reference/kotlin/androidx/compose/material3/adaptive/layout/SupportingPaneScaffold)
- [Material 3 Adaptive Layouts - Android Developers](https://developer.android.com/develop/ui/compose/layouts/adaptive)

### 4.3 浏览器历史导航替代方案

**现状替代**：UI 返回按钮 + Web `PlatformBrowserBackHandler` + 统一 `TransientBackConsumer` 弹层返回链

**优点**：
- 用户可见的返回路径始终可用
- 无额外依赖
- supporting pane 等局部弹层接入统一返回链路，无须 `NavigableSupportingPaneScaffold`

**缺点**：
- 当前已按 Nav3 回退深度 + pane 状态同步 Back，但不维护完整 forward 可恢复序列

**后续接入路径**：
1. **已完成短期方案**：WasmJS `backStackDepth` + `popstate` 桥接到 `Navigator.goBack()`，pane 通过 `RegisterTransientBack` 计入深度
2. **中期**：如需要完整 history 序列，再评估 `navigation3-browser` POC 库
3. **长期**：继续关注官方 Navigation 3 浏览器历史能力

**参考链接**：
- [navigation3-browser POC 库](https://github.com/terrakok/navigation3-browser)
- [Kotlin Multiplatform Compose Navigation 3 — Browser History](https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html#recommended-serialization-approaches)

---

## 五、版本快照

| 组件 | 版本 |
|------|------|
| Compose Multiplatform (CMP) | `1.11.0-rc01` |
| Material3 Adaptive | `1.3.0-alpha07`（基于 AndroidX 1.3.0-alpha10） |
| Navigation 3 UI | 已引入 (`org.jetbrains.androidx.navigation3:navigation3-ui`) |
| Material3 Adaptive Navigation3 | 已引入 (`org.jetbrains.compose.material3.adaptive:adaptive-navigation3`) |
| Navigation 3 Browser | **未引入** (`com.github.terrakok:navigation3-browser`) |
| Kotlin | `2.3.20` |

---

## 六、建议的下一步

1. **中优先级**：如产品需要完整浏览器前进/后退序列，再评估 `navigation3-browser`
