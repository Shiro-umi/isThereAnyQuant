# 移动端 Debug 功能开关与网络日志悬浮窗前期设计

> 状态: 前期设计
> 适用范围: Android 客户端优先，预留 iOS/KMP 扩展能力
> 日期: 2026-04-29

## 1. 背景与目标

移动端 release 包在真实网络环境下排查接口调用、Token 刷新、WebSocket 连接和后端错误时，当前只能依赖系统日志或用户口头反馈，定位成本高。目标是在 App 内提供一个隐藏开启的 debug 功能开关；开关开启后，显示全局悬浮日志窗口，用于查看网络请求、响应、异常和 WebSocket 事件。

该能力服务于移动端排障，不改变正常业务功能，也不依赖 `BuildConfig.DEBUG` 或部署 `quant.mode`。release 包中默认关闭，只有通过隐藏手势开启后才采集和展示。

## 2. 业务流程链路

完整链路如下:

```text
用户在登录页右上 1/4 区域完成隐藏手势
  -> Debug 功能开关切换为开启
  -> 全局 Debug 状态持久化
  -> AuthGate 顶层展示 DebugOverlayHost
  -> REST / WebSocket 统一入口写入 NetworkDebugLogStore
  -> DebugOverlayHost 展示可滚动、可追溯的网络日志列表
```

涉及的现有业务入口:

| 环节 | 现有位置 | 设计接入点 |
|------|----------|------------|
| 登录态网关 | `compose-app/.../ui/auth/AuthGate.kt` | 全局悬浮窗挂载点 |
| 登录页 | `compose-app/.../feature/auth/LoginScreen.kt` | 隐藏手势识别区域 |
| REST 客户端 | `HttpClientProvider` / `Network.kt` | HTTP 请求、响应、异常采集 |
| WebSocket 客户端 | `GlobalWebSocketClient.kt` | 连接、重连、命令、事件、错误采集 |
| Android 入口 | `MainActivity.kt` | 如需初始化平台存储，放在 Android actual 内部 |

## 3. 功能边界

本设计只覆盖移动端 App 内 debug 能力，不新增服务端接口，不改变后端日志，不改变部署端口和打包流程。

第一阶段目标:

- Android release 包默认关闭 debug 功能。
- 登录页隐藏手势可打开或关闭 debug 功能。
- 开启后展示全局悬浮窗，未登录和已登录状态都可见。
- 记录 REST 请求、响应、异常。
- 记录 WebSocket 连接、断开、重连、命令发送、事件接收、解析失败。
- 列表支持历史滚动，至少保留最近 300 条。
- Token、Cookie、Authorization 等敏感字段必须脱敏。

非目标:

- 不把日志上传服务器。
- 不持久化详细网络日志。
- 不做完整抓包替代品。
- 不为每个 Repository 增加单独日志代码。
- 不引入与现有 `quant.mode` 绑定的 debug/release 语义变化。

## 4. 模块设计

建议新增一个前端公共功能域:

```text
compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/debug/
  DebugFeatureController.kt
  DebugSettingsStore.kt
  NetworkDebugLogStore.kt
  NetworkDebugLogModels.kt
  DebugOverlayHost.kt
  LoginDebugGestureDetector.kt
```

平台存储预留:

```text
compose-app/src/androidMain/kotlin/org/shiroumi/quant_kmp/debug/DebugSettingsStore.android.kt
compose-app/src/jsMain/kotlin/org/shiroumi/quant_kmp/debug/DebugSettingsStore.js.kt
compose-app/src/jvmMain/kotlin/org/shiroumi/quant_kmp/debug/DebugSettingsStore.jvm.kt
```

iOS 未来如果加入 KMP target，只需要补:

```text
compose-app/src/iosMain/kotlin/org/shiroumi/quant_kmp/debug/DebugSettingsStore.ios.kt
```

### 4.1 DebugFeatureController

职责:

- 管理 debug 功能开关。
- 暴露 `StateFlow<Boolean>` 给 UI 和网络采集层。
- 初始化时从平台存储读取开关状态。
- 手势成功后切换状态并写回平台存储。

约束:

- 开关只表示“App 内 debug 功能是否开启”，不等同于开发环境。
- 关闭后悬浮窗消失，网络采集层停止写入新日志。
- 可选择关闭时清空历史日志，减少敏感信息驻留。

### 4.2 DebugSettingsStore

建议使用 expect/actual:

```kotlin
expect object DebugSettingsStore {
    fun readDebugEnabled(): Boolean
    fun writeDebugEnabled(enabled: Boolean)
}
```

平台策略:

| 平台 | 存储 |
|------|------|
| Android | `SharedPreferences` |
| iOS 未来 | `NSUserDefaults` |
| JS/Web | `localStorage`，仅用于本地调试或 Web 兼容 |
| JVM | 内存或本地 properties，保持编译契约 |

### 4.3 NetworkDebugLogStore

职责:

- 作为 App 内网络日志的单一写入点。
- 使用内存环形缓冲，避免无限增长。
- 通过 `StateFlow<List<NetworkDebugLogEntry>>` 给悬浮窗展示。

建议字段:

```text
id: Long
timestampMillis: Long
category: HTTP | WEBSOCKET | AUTH | SYSTEM
level: INFO | WARN | ERROR
direction: OUTBOUND | INBOUND | INTERNAL
title: String
method: String?
url: String?
statusCode: Int?
durationMillis: Long?
summary: String
detail: String?
```

容量建议:

- 默认最多 300 条。
- 单条 detail 最多 4KB 到 8KB。
- 超长 body、payload、stack trace 截断并标记 `truncated=true`。

敏感信息规则:

- `Authorization` 只显示 scheme，不显示 token。
- Cookie 不展示原值。
- WebSocket URL query 中的 `token` 必须脱敏。
- 登录、注册、修改密码请求 body 默认不展示。
- access token、refresh token、password、cookie、secret、credential 等 key 必须按 key 名脱敏。

## 5. 隐藏手势设计

触发位置:

- 仅登录页生效。
- 区域为屏幕右上 1/4，即 `x >= width / 2 && y <= height / 2`。

目标序列:

```text
c -> l -> c -> t -> c -> r -> c -> b -> c
```

含义:

| 代号 | 含义 |
|------|------|
| c | 右上 1/4 区域中心 |
| l | 从中心向左移动 |
| t | 从中心向上移动 |
| r | 从中心向右移动 |
| b | 从中心向下移动 |

识别规则:

- pointer down 必须落在右上 1/4 区域内。
- 首个点需要接近该区域中心，建议中心容忍半径为 `32.dp`。
- 每个方向步骤只要相对中心位移超过 `20.dp` 即判定成功。
- 横向步骤主要判断 `abs(dx) > 20.dp`，允许一定纵向误差。
- 纵向步骤主要判断 `abs(dy) > 20.dp`，允许一定横向误差。
- 回到 `c` 时，当前点回到中心容忍半径内即判定成功。
- 超时、离开右上 1/4 区域过远、多指触控或 pointer up 未完成时 reset。

建议容错:

- 中心容忍半径: `32.dp`。
- 方向阈值: `20.dp`。
- 单次完整手势超时: `8s`。
- 方向正交误差允许到 `48.dp`，避免手指轻微斜画失败。

成功反馈:

- 切换开关后展示短 Toast 或 Snackbar。
- 开启后立刻显示悬浮入口。
- 关闭后移除悬浮窗。

## 6. 悬浮窗 UI 设计

挂载位置:

- 推荐挂在 `AuthGate` 顶层 `Box` 内，保证登录页和主应用都可显示。
- 不挂在单个业务页面中，避免导航切换丢失。

交互状态:

| 状态 | 行为 |
|------|------|
| 收起 | 右侧或右下角小悬浮入口，显示错误数量或最新状态 |
| 展开 | 半屏面板展示日志列表 |
| 详情 | 点击单条日志展开 detail |

基础控件:

- 清空日志。
- 暂停自动跟随底部。
- 分类过滤: All / HTTP / WS / Error。
- 关闭 debug 开关。

列表行为:

- 使用 `LazyColumn` 展示。
- 默认跟随最新日志。
- 用户手动向上滚动后停止自动贴底。
- 错误日志使用更明显的颜色，但避免影响整体可读性。

移动端尺寸建议:

- 收起入口: `48.dp` 左右。
- 展开高度: 屏幕高度的 45%-60%。
- 宽度: 小屏 `fillMaxWidth()`，左右保留安全区 padding。
- 大屏或平板可使用固定宽度，例如 `420.dp`。

动效:

- 使用项目统一的 decelerate easing。
- 展开/收起使用短时长尺寸与透明度过渡。
- 禁止弹跳、闪烁或过慢动画。

## 7. REST 采集设计

REST 不应在每个 Repository 中单独埋点，应接入统一 HttpClient 配置。

建议采集位置:

- `configureCommon()` 中安装 Ktor 客户端插件。
- `HttpClientProvider.authClient` 与 `apiClient` 都使用同一套采集。
- `AuthInterceptor` 中的 401 刷新和重试需要单独记录为 `AUTH` 或 `HTTP` 内部事件。

记录内容:

- 请求方法、URL path、query。
- 开始时间、结束时间、耗时。
- HTTP status。
- 是否异常。
- 错误 message。
- 可选响应摘要。

注意事项:

- release 环境下只有 debug 开启才写入 `NetworkDebugLogStore`。
- 不要把 Ktor `Logging` 的明文日志直接搬到 UI，因为它可能包含敏感 header 或 body。
- `isDevelopmentMode()` 仍只控制控制台日志，不控制 App 内 debug 功能。

## 8. WebSocket 采集设计

WebSocket 现有统一入口是 `GlobalWebSocketClient`，应在该对象中集中记录。

建议采集点:

- 准备连接: `CONNECTING`
- 连接成功: `CONNECTED`
- 连接异常: `CONNECT_ERROR`
- 连接关闭: `CLOSED`
- 非主动断开后的重连等待: `RETRY_SCHEDULED`
- command 入队: `COMMAND_QUEUED`
- command 发送成功: `COMMAND_SENT`
- command 发送失败: `COMMAND_SEND_ERROR`
- event 接收并解析成功: `EVENT_RECEIVED`
- payload 解析失败: `EVENT_PARSE_ERROR`
- topic 内部业务错误: `EVENT_ERROR`

关键安全要求:

- 当前连接日志如果包含完整 `wsUrl`，必须改为脱敏 URL。
- `?token=...` 只能展示为 `?token=***`。
- payload 需要截断，且敏感字段脱敏。

## 9. 安全与隐私

该功能进入 release 包，因此安全规则必须作为实现约束:

- 默认关闭。
- 只有隐藏手势开启。
- 日志仅保存在内存中。
- 不自动上传。
- 不写入本地文件。
- token、cookie、password、secret 等字段强制脱敏。
- 单条日志长度限制。
- 关闭 debug 时建议清空日志。
- 悬浮窗不展示完整 Authorization header。

如后续要增加“导出日志”，需要单独设计用户确认、脱敏、文件生命周期和分享渠道，不纳入第一阶段。

## 10. 与部署和 release 语义的关系

本设计不改变部署架构:

- 不修改 `deploy.sh`。
- 不修改 `quant.mode`。
- 不修改 debug/release 端口。
- 不修改 Ktor 静态资源和 APK 打包链路。

`debug 功能开关` 是 App 内运行时能力，和部署模式解耦:

| 概念 | 含义 |
|------|------|
| `BuildConfig.DEBUG` | Android 构建类型是否 debug |
| `quant.mode` | 项目部署和前端编译环境模式 |
| Debug 功能开关 | 用户通过隐藏手势开启的 App 内排障能力 |

## 11. 后续落地步骤

建议按以下顺序实现:

1. 新增 `DebugFeatureController`、`DebugSettingsStore` 和 `NetworkDebugLogStore`。
2. 在 `AuthGate` 顶层挂载 `DebugOverlayHost`。
3. 在 `LoginScreen` 增加右上 1/4 隐藏手势识别。
4. 在 REST 统一 HttpClient 配置中写入 HTTP 日志。
5. 在 `GlobalWebSocketClient` 中写入 WS 日志，并脱敏现有 token URL 输出。
6. 完成悬浮窗 UI 的收起、展开、过滤、清空、滚动行为。
7. 补充手势识别和日志环形缓冲的单元测试。
8. 运行 `./gradlew :compose-app:build` 验证跨端编译。

## 12. 验收标准

- Android release 包安装后默认不显示 debug 悬浮窗。
- 在登录页右上 1/4 区域完成指定手势后，debug 悬浮入口出现。
- 再次完成手势可以关闭 debug 功能。
- 开启后，登录接口请求、失败响应、Token 刷新失败能够在悬浮窗中看到。
- 登录后，业务 REST 请求和 WebSocket 连接、重连、订阅、错误事件能够在悬浮窗中看到。
- 日志列表可以回溯滚动，超过容量后旧日志被淘汰。
- 日志中不出现明文 access token、refresh token、cookie 或 password。
- 关闭 debug 功能后不再新增日志，悬浮窗消失。

## 13. 文档同步判断

本文是前期设计文档，不代表架构已实现。当前阶段不更新 `kmp-compose-frontend-architecture.md`、`ui-architecture.md` 或 `deployment-architecture.md`。

后续实际落地代码时，如果新增全局 debug 状态、网络采集层或全局悬浮窗，需要同步更新 KMP Compose 和 UI 架构 reference；若不改变部署脚本、模式、端口和打包链路，则部署 reference 仍无需更新。
