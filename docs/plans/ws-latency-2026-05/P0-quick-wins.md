# P0：WS 压缩 + 连接状态 + 切股 watchdog

> 目标：用最小改动覆盖 review 中估算 ROI 最高的三项治理。一个 PR 落地。
> 改动文件 ≤ 6 个，不动协议结构，不动 convergenceKey 设计。

## 1. 业务链路定位

| 子项 | 触及链路段 | 现状问题 |
|---|---|---|
| P0.1 压缩 | `浏览器 ⇄ Cloudflare ⇄ Ktor /ws/app-stream` 出向帧 | 100–200 KB K 线 JSON 明文穿隧道，首屏感知 600–1500ms |
| P0.2 连接状态 | `GlobalWebSocketClient` 内部 isConnected → ViewModel/UI | 状态私有，断线期 UI 无反馈 |
| P0.3 切股 watchdog | `CandleViewModel.startCandleSubscription` | 命令被默默 drop 时 isLoadingCandle 无限卡住 |

## 2. P0.1 — Ktor WebSocket permessage-deflate

### 2.1 改动点

**服务端**：[ktor-server/src/main/kotlin/ktor/module/WebSockets.kt](../../../ktor-server/src/main/kotlin/ktor/module/WebSockets.kt)

```kotlin
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.WebSocketDeflateExtension
import java.util.zip.Deflater

fun Application.websockets() = install(WebSockets) {
    pingPeriod = Duration.parse("15s")
    timeout = Duration.parse("45s")
    maxFrameSize = MAX_APP_STREAM_FRAME_BYTES
    masking = false
    extensions {
        install(WebSocketDeflateExtension) {
            compressionLevel = Deflater.DEFAULT_COMPRESSION
            // 小于 4 KB 的帧（控制帧、SUBSCRIBE ACK 等）不压缩，节省 CPU
            compressIfBiggerThan(4 * 1024)
        }
    }
}
```

**客户端**：仅 Android（OkHttp engine）安装 deflate。wasmJs target 默认不安装，依赖 RFC 7692 协议自身的握手协商兜底——客户端不在 `Sec-WebSocket-Extensions` 中声明 `permessage-deflate` 时，服务端会自动跳过压缩。

具体修改位置：[compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/Network.kt](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/Network.kt) 提供 `createPlatformHttpClient`，需在 androidMain 的实现里加 extension；wasmJs 实现保持现状。

### 2.2 为什么 wasm 不开启

- Ktor 3.x wasmJs target 的 WebSocket 客户端在原生 `WebSocket` API 上，不暴露 extension 注册接口。
- 浏览器原生 WebSocket API（spec）不允许 JS 层声明 extensions——只有浏览器底层在 HTTP Upgrade 时如果开启 deflate 才会带上。Chromium/Firefox 默认开启。这意味着 wasm 实际链路上多数仍能获得压缩，但**协商主体是浏览器而非 Ktor client**，无需在 Ktor 层做任何事。

### 2.3 影响面

| 维度 | 影响 |
|---|---|
| 协议结构 | 无（standard RFC 7692 extension 在帧层透明） |
| 心跳/timeout | 无 |
| convergenceKey / commandSeq | 无 |
| CPU | server 端单条 K 线 JSON deflate 约 1–3ms，trade off 远小于网络节省 |
| 内存 | 每连接 deflater state 约 256 KB（Java zlib 默认）|
| 兼容性 | Cloudflare WebSocket 透传 extension header，已在多个项目验证 |

### 2.4 验证

1. **协商验证**：DevTools → Network → 找到 `app-stream` 升级请求。响应头应包含 `Sec-WebSocket-Extensions: permessage-deflate`（浏览器与服务端协商成功）。
2. **字节数验证**：DevTools → Network → WS → 找到 SYNC 那一帧，对比修改前后 size。预期：500 根日 K + indicators 从 ~150 KB 降到 ~30–50 KB。
3. **公网体感验证**：release 部署后切股 → K 线显示首字节时间。预期从 1–2s 降到 300–600ms（其余延迟由 Cloudflare RTT + 后端处理构成）。
4. **服务端日志**：观察是否有 "extension negotiation failed" 类警告。如有，记录用户 agent + 端环境，单独排查。

### 2.5 回滚

删除服务端 `extensions { ... }` 块（一段，3 行），删除 Android 端 client extension 注册。两端独立可回滚。

---

## 3. P0.2 — 暴露 connectionStateFlow

### 3.1 改动点

**GlobalWebSocketClient**（[compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/service/GlobalWebSocketClient.kt:73-87](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/service/GlobalWebSocketClient.kt)）：

新增枚举与 StateFlow：

```kotlin
enum class ConnectionState {
    DISCONNECTED,   // 初始或手动 disconnect 后
    CONNECTING,     // connectJob active, 尚未握手
    CONNECTED,      // 握手成功
    RECONNECTING    // 非主动断开，正在指数退避
}

private val _connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
val connectionStateFlow: StateFlow<ConnectionState> = _connectionStateFlow.asStateFlow()
```

**connect() 内部状态机更新**：

- `connect()` 进入 connectJob 循环顶部：`_connectionStateFlow.value = ConnectionState.CONNECTING`
- `httpClient.webSocket { ... }` 块进入后第一行（紧跟 `webSocketSession = this`、`isConnected = true`）：`_connectionStateFlow.value = ConnectionState.CONNECTED`
- catch 或 finally 中、非 `isManualDisconnect`、即将走 `delay(reconnectDelay)`：`_connectionStateFlow.value = ConnectionState.RECONNECTING`
- `disconnect()` 末尾：`_connectionStateFlow.value = ConnectionState.DISCONNECTED`

**CandleViewModel**：

新增 observer（[compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt:143-150](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt) init 中追加）：

```kotlin
init {
    loadStocks()
    loadSentimentHistory()
    observeMarketStatus()
    observeStockListPolling()
    observeIntradaySnapshot()
    observeStrategySelections()
    observeConnectionState()   // 新增
}

private fun observeConnectionState() {
    viewModelScope.launch {
        GlobalWebSocketClient.connectionStateFlow.collect { state ->
            _state.value = _state.value.copy(connectionState = state)
        }
    }
}
```

**Contract**：CandleContract.State 增字段。具体路径：

```kotlin
// compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/contract/CandleContract.kt
data class State(
    // 已有字段...
    val connectionState: ConnectionState = ConnectionState.CONNECTED,
)
```

`ConnectionState` 在 GlobalWebSocketClient 内是 nested enum，Contract 引用即可。如果 Contract 在更上层不希望依赖 service 层，则将 `ConnectionState` 提到 `service/ConnectionState.kt` 单独文件再被双方引用。

**UI**：

在 `KLineDetailPanel` / `CandleScreen` 中 loading 状态展示分支，新增 connectionState 判断（实际文件路径待 P0.2 实施时查）：

- `isLoadingCandle && connectionState == CONNECTED` → 普通骨架屏
- `isLoadingCandle && connectionState in (CONNECTING, RECONNECTING)` → "网络重连中…"提示

### 3.2 影响面

| 维度 | 影响 |
|---|---|
| 协议 | 无 |
| 其他 ViewModel | 不接入即不感知，IntradayViewModel / TrackingViewModel 可在后续 PR 同样接入 |
| AuthGate 初始化 | 不变（仍按 token 触发 connect） |
| restoreSubscriptions | 不变（仍由握手成功后触发） |

### 3.3 验证

1. 正常连接：观察 connectionState 从 DISCONNECTED → CONNECTING → CONNECTED 的顺序变化（可临时加 debug log 验证）。
2. kill server 进程：前端 1s 内变 RECONNECTING；重启 server 后恢复 CONNECTED。
3. 切股期间断网：UI 应立即显示"网络重连中…"，恢复后自动 hydrate K 线。

### 3.4 回滚

- 删除 `_connectionStateFlow` 相关代码 + Contract 字段 + UI 文案分支。
- 三处独立。

---

## 4. P0.3 — Candle 切股 watchdog

### 4.1 改动点

**CandleViewModel** ([compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt:113-118](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt) 字段区追加)：

```kotlin
private var candleWatchdogJob: Job? = null
private companion object {
    const val CANDLE_WATCHDOG_TIMEOUT_MS = 8_000L
    const val CANDLE_WATCHDOG_TICK_MS = 500L
}
```

**startCandleSubscription** ([CandleViewModel.kt:325-428](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt)) 改动：

```kotlin
private fun startCandleSubscription() {
    val tsCode = _state.value.selectedStock?.code ?: return
    val period = _state.value.selectedPeriod
    activeCandleRequestSeq += 1L
    val requestSeq = activeCandleRequestSeq

    candleSubscriptionJob?.cancel(); candleSubscriptionJob = null
    candleRetryJob?.cancel(); candleRetryJob = null
    candleWatchdogJob?.cancel(); candleWatchdogJob = null   // 新增

    _state.value = _state.value.copy(isLoadingCandle = true, candleError = null)

    candleSubscriptionJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        GlobalWebSocketClient.candleEventsFlow(tsCode, period).collectLatest { event ->
            when (event) {
                is GlobalWebSocketClient.CandleStreamEvent.Data -> {
                    // ... 已有逻辑 ...
                    _state.value = _state.value.copy(
                        candles = candles,
                        chartData = chartData,
                        isLoadingCandle = false,
                        candleError = null
                    )
                    candleWatchdogJob?.cancel()      // 新增：成功后取消 watchdog
                    candleWatchdogJob = null
                }
                is GlobalWebSocketClient.CandleStreamEvent.Error -> {
                    // ... 已有逻辑 ...
                    // 注意：可重试错误 → scheduleCandleRetry，watchdog 也需 reset
                    if (event.payload.errorCode.isRetryable()) {
                        scheduleCandleRetry(tsCode, period)
                        resetCandleWatchdog(tsCode, period, requestSeq)  // 新增
                    } else {
                        _state.value = _state.value.copy(
                            isLoadingCandle = false,
                            candleError = event.payload.message
                        )
                        candleWatchdogJob?.cancel()
                        candleWatchdogJob = null
                    }
                }
            }
        }
    }

    GlobalWebSocketClient.subscribeCandle(
        tsCode = tsCode, period = period,
        limit = DEFAULT_CANDLE_WINDOW_LIMIT, useAdjusted = true,
        requestSeq = requestSeq
    )

    resetCandleWatchdog(tsCode, period, requestSeq)   // 新增
}

/**
 * 启动或重置切股 watchdog。
 *
 * 设计要点：
 * - 8s 窗口内必须收到任意一个 Data 或终态 Error，否则结束 loading 并显示超时
 * - 断线期间（connectionState != CONNECTED）暂停倒计时，避免重连本身被算成超时
 * - 用户切到其他股票/周期时 watchdog 自然失效（startCandleSubscription cancel 旧 job）
 */
private fun resetCandleWatchdog(
    tsCode: String,
    period: CandlePeriod,
    requestSeq: Long
) {
    candleWatchdogJob?.cancel()
    candleWatchdogJob = viewModelScope.launch {
        var remainingMs = CANDLE_WATCHDOG_TIMEOUT_MS
        while (isActive && remainingMs > 0) {
            delay(CANDLE_WATCHDOG_TICK_MS)
            // 用户切到其他股票/周期，watchdog 失效
            if (_state.value.selectedStock?.code != tsCode ||
                _state.value.selectedPeriod != period ||
                requestSeq != activeCandleRequestSeq) {
                return@launch
            }
            // 已经收到数据，watchdog 失效
            if (_state.value.candles.isNotEmpty()) {
                return@launch
            }
            // 断线期间不计时
            if (GlobalWebSocketClient.connectionStateFlow.value == ConnectionState.CONNECTED) {
                remainingMs -= CANDLE_WATCHDOG_TICK_MS
            }
        }
        // 超时兜底
        if (_state.value.candles.isEmpty() &&
            _state.value.selectedStock?.code == tsCode &&
            _state.value.selectedPeriod == period &&
            requestSeq == activeCandleRequestSeq) {
            _state.value = _state.value.copy(
                isLoadingCandle = false,
                candleError = "K线加载超时，请检查网络后重试"
            )
        }
    }
}
```

**stopCandleSubscription** ([CandleViewModel.kt:584-590](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt)) 与 **onCleared** ([CandleViewModel.kt:854-862](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt)) 末尾加：

```kotlin
candleWatchdogJob?.cancel()
candleWatchdogJob = null
```

### 4.2 影响面

| 维度 | 影响 |
|---|---|
| 已有 scheduleCandleRetry | 与 watchdog 互补——retry 处理"server 主动 ERROR 可重试"，watchdog 处理"没有任何响应"。retry 触发时 watchdog 重置。|
| 用户重试入口 | 现有 RefreshCandle action 已支持重试，watchdog 超时后用户可点击重试按钮 |
| 多 NavEntry 引用计数 | 不影响——watchdog 跟随 startCandleSubscription 启停，与 screenActiveRefCount 无耦合 |

### 4.3 验证

1. 本地正常切股：观察日志，watchdog 在 SYNC 到达后立即 cancel。
2. 关闭 server：切股 8s 后 UI 显示超时错误 + 可点击重试。
3. Mock 断网 10s 再恢复：watchdog 暂停计时，恢复后继续兜底直到收到数据。
4. 快速切股 A→B→C→D：仅最后 D 的 watchdog 存活，前三个被 cancel。

### 4.4 回滚

删除 `candleWatchdogJob` 字段、`resetCandleWatchdog` 函数、三处 cancel 点。单点删除，不影响其他逻辑。

---

## 5. 提交结构

建议单 PR 但分 commit：

1. `feat(ws): enable permessage-deflate extension on server and android client`
2. `feat(ws): expose connectionStateFlow from GlobalWebSocketClient and wire CandleScreen`
3. `feat(candle): add 8s watchdog for candle subscription with connection-state pause`

PR 描述模板见 [engineering-discipline.md](engineering-discipline.md#pr-描述模板)。

## 6. 实施前需要先做的事

- [ ] 确认 Ktor 版本：libs.versions.toml 中 ktor 应为 3.x；WebSocketDeflateExtension 的 import 路径与 API。
- [ ] 找到客户端 androidMain 的 HttpClient engine 配置位置（grep `createPlatformHttpClient` 在 androidMain 的 actual 实现）。
- [ ] 查 CandleContract.State 当前所有字段，找一个合适的位置插 `connectionState`。
- [ ] 找到 KLineDetailPanel / CandleScreen 中处理 isLoadingCandle 的 Composable 分支。
