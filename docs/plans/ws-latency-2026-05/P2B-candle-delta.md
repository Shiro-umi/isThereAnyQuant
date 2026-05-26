# P2.B：CANDLE_DATA delta 推送

> 目标：盘中实时 UPDATE 不再全量推 500 根 K 线，只推变化的尾部。
> 估算带宽降幅 90%+。
> 工作量 3–5 人天。**风险等级中高，需要协议版本协商、客户端合并兜底、回归测试。**
> 依赖：P2.A 已落地（delta 也要支持 indicators 尾部 patch）。

## 1. 业务链路定位

```
SyncLooper (每秒) → CandleDataProvider.onDataSync(changedKeys)
  → facade.readSnapshot(key) → CandleSnapshotState
  → projectionService.project → CandleDataPayload  (当前总是全量 500 根)
  → composePayloadJson + sendToSession  (~150 KB / 帧，deflate 后 ~30–50 KB)
```

盘中 DAY 线场景下，每秒变化的只有 `candles.last()`（当日实时 K）。前 499 根原样发送是纯浪费。

## 2. 协议设计

### 2.1 版本协商

`CandleSubscribeRequest` 增字段：

```kotlin
@Serializable
data class CandleSubscribeRequest(
    val tsCode: String,
    val period: CandlePeriod = CandlePeriod.DAY,
    val limit: Int? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val useAdjusted: Boolean = true,
    val requestSeq: Long? = null,
    val clientProtocolVersion: Int = 1   // 新增：1=legacy 全量，2=支持 delta
)
```

server 根据该字段决定推送格式：
- `clientProtocolVersion = null 或 1` → 永远推全量（兼容旧客户端）
- `clientProtocolVersion >= 2` → SYNC 仍全量，后续 UPDATE 可推 delta

### 2.2 Payload 演进

**v1（保留）**：
```kotlin
data class CandleDataPayload(
    val tsCode: String,
    val candles: List<CandleData>,
    val totalCount: Int,
    val indicators: CandleIndicatorBundle? = null,
    val requestParams: CandleSubscribeRequest? = null,
    val protocolVersion: Int = 1
)
```

**v2 新增**：
```kotlin
data class CandleDataPayloadV2(
    val tsCode: String,
    val event: CandleSnapshotEvent,
    val totalCount: Int,
    val requestParams: CandleSubscribeRequest? = null,
    val protocolVersion: Int = 2
)

@Serializable
sealed interface CandleSnapshotEvent {
    @SerialName("sync")
    @Serializable
    data class Sync(
        val candles: List<CandleData>,
        val indicators: CandleIndicatorBundle?,
        val version: Long
    ) : CandleSnapshotEvent

    @SerialName("delta")
    @Serializable
    data class Delta(
        val baseVersion: Long,         // 上一次 client 已知 version
        val version: Long,             // 当前 version
        val replaceFromIndex: Int,     // 替换起点（通常 = totalCount - 1）
        val replaceCandles: List<CandleData>,
        val indicatorsPatch: CandleIndicatorPatch?
    ) : CandleSnapshotEvent
}

@Serializable
data class CandleIndicatorPatch(
    val replaceFromIndex: Int,     // 与 candles 的 replaceFromIndex 对齐
    val ema: Map<Int, List<Float?>>,
    val ma: Map<Int, List<Float?>>,
    val rsi: Map<Int, List<Float?>>,
    val macd: MacdSeries,
    val boll: BollSeries
)
```

**关键约束**：
- Delta 只在窗口（startDate/endDate/limit）不变时使用。窗口变化必须 SYNC。
- `replaceFromIndex` 必须 ≤ 当前客户端 candles.size，否则客户端必须主动 re-SYNC。
- `baseVersion` 必须等于客户端本地最新 version，否则客户端 re-SYNC。

## 3. server 实现

### 3.1 上下文记录

[CandleDataProvider](../../../ktor-server/src/main/kotlin/org/shiroumi/server/data/provider/CandleDataProvider.kt) 已有 `lastSentVersion: ConcurrentHashMap<CandleKey, Long>`。扩展为按 session 维度：

```kotlin
private data class LastSentMeta(
    val version: Long,
    val windowStartDate: String?,   // 当前窗口首根 K 线日期
    val windowEndDate: String?,
    val totalCount: Int,
    val protocolVersion: Int        // 该 session 协商的版本
)
private val lastSentMeta = ConcurrentHashMap<Pair<DefaultWebSocketServerSession, CandleKey>, LastSentMeta>()
```

### 3.2 推送决策

`sendSnapshot` 内部增加分支：

```kotlin
private suspend fun sendSnapshot(
    session: DefaultWebSocketServerSession,
    key: CandleKey,
    request: CandleSubscribeRequest,
    snapshot: CandleSnapshotState,
    actionHint: WsAction   // 改成 hint，最终 action 由 delta 判定逻辑决定
) {
    val protocolVersion = request.clientProtocolVersion ?: 1
    val payload = projectionService.project(key, request, snapshot)
    val meta = lastSentMeta[session to key]

    val canDelta = protocolVersion >= 2 &&
        meta != null &&
        meta.windowStartDate == payload.candles.firstOrNull()?.date &&
        meta.windowEndDate == payload.candles.lastOrNull()?.date?.let { /* prev date */ } &&
        meta.totalCount == payload.totalCount

    val outgoing = if (canDelta) {
        buildDeltaEvent(meta!!, payload, snapshot.version)
    } else {
        buildSyncEvent(payload, snapshot.version)
    }

    AppWebSocketConnectionManager.sendToSession(session, outgoing)
    lastSentMeta[session to key] = LastSentMeta(
        version = snapshot.version,
        windowStartDate = payload.candles.firstOrNull()?.date,
        windowEndDate = payload.candles.lastOrNull()?.date,
        totalCount = payload.totalCount,
        protocolVersion = protocolVersion
    )
}
```

### 3.3 delta 内容确定

最常见的 delta 场景：当日 K 线 close/high/low/volume 在变。Delta 应：
- `replaceFromIndex = payload.totalCount - 1`
- `replaceCandles = [payload.candles.last()]`
- `indicatorsPatch.replaceFromIndex = totalCount - 1`，每个 series 只取最后一个值

如果上一根也变了（极少见的 minute 周期跨日补数据），扩展为最后 N 根：
- 找出 `payload.candles` 与 server 端记录的 `previousCandles` 差异点，取最早差异点作为 `replaceFromIndex`

简化策略：**P2.B v1 实现只支持"替换最后 1 根"**。如有跨多根变化场景，回退 SYNC。后续按需扩展。

### 3.4 encodedCache 行为

- Sync 路径：仍可缓存 candles JSON + indicators JSON（同 P2.A 设计）
- Delta 路径：每次 version 都不同，缓存命中率近 0 → 不缓存 delta 输出

## 4. 客户端实现

### 4.1 GlobalWebSocketClient

[handleGlobalEvent CANDLE_DATA 分支](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/service/GlobalWebSocketClient.kt:374-414) 增协议版本判别：

```kotlin
WsTopic.CANDLE_DATA -> {
    val payloadJson = event.payload ?: return
    val protocolVersion = json.parseToJsonElement(payloadJson)
        .jsonObject["protocolVersion"]?.jsonPrimitive?.intOrNull ?: 1

    val candleEvent: CandleStreamEvent? = when (event.action) {
        WsAction.SYNC, WsAction.UPDATE -> when (protocolVersion) {
            1 -> CandleStreamEvent.LegacyData(payloadJson)
            else -> CandleStreamEvent.V2Data(payloadJson)
        }
        WsAction.ERROR -> /* ... */
        else -> null
    }
    // _candleEventsFlow.tryEmit(...)
}
```

`CandleStreamEvent` 扩展为：

```kotlin
sealed interface CandleStreamEvent {
    data class LegacyData(val payloadJson: String) : CandleStreamEvent {
        fun decode(json: Json): CandleDataPayload = json.decodeFromString(payloadJson)
    }
    data class V2Data(val payloadJson: String) : CandleStreamEvent {
        fun decode(json: Json): CandleDataPayloadV2 = json.decodeFromString(payloadJson)
    }
    data class Error(val payload: CandleErrorPayload) : CandleStreamEvent
}
```

发送 SUBSCRIBE_CANDLE 时附带 `clientProtocolVersion = 2`：
```kotlin
fun subscribeCandle(...) {
    val request = CandleSubscribeRequest(
        // ...
        clientProtocolVersion = 2
    )
    // ...
}
```

### 4.2 CandleViewModel

[collectLatest body](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt:341-419) 分协议处理：

```kotlin
when (event) {
    is CandleStreamEvent.LegacyData -> handleLegacyData(event, tsCode, period, requestSeq)
    is CandleStreamEvent.V2Data -> handleV2Data(event, tsCode, period, requestSeq)
    is CandleStreamEvent.Error -> /* ... */
}

private suspend fun handleV2Data(
    event: CandleStreamEvent.V2Data,
    tsCode: String,
    period: CandlePeriod,
    requestSeq: Long
) {
    val payload = runCatching { event.decode(jsonForCandle) }.getOrNull() ?: return
    if (payload.requestParams?.requestSeq != null && payload.requestParams.requestSeq != requestSeq) return

    when (val snapshot = payload.event) {
        is CandleSnapshotEvent.Sync -> applySync(snapshot, payload)
        is CandleSnapshotEvent.Delta -> applyDelta(snapshot, payload, tsCode, period, requestSeq)
    }
}

private suspend fun applyDelta(
    delta: CandleSnapshotEvent.Delta,
    payload: CandleDataPayloadV2,
    tsCode: String,
    period: CandlePeriod,
    requestSeq: Long
) {
    val current = _state.value.chartData
    val currentVersion = current?.snapshotVersion ?: 0L

    // baseVersion 不对 → 重新 SYNC
    if (delta.baseVersion != currentVersion) {
        traceLog("delta baseVersion mismatch, requesting re-SYNC")
        GlobalWebSocketClient.subscribeCandle(
            tsCode = tsCode, period = period,
            limit = DEFAULT_CANDLE_WINDOW_LIMIT, useAdjusted = true,
            requestSeq = requestSeq
        )
        return
    }
    if (delta.replaceFromIndex > (current?.candles?.size ?: 0)) {
        traceLog("delta replaceFromIndex out of bounds, requesting re-SYNC")
        // 同上 re-SYNC
        return
    }

    val merged = current!!.candles.take(delta.replaceFromIndex) + delta.replaceCandles.toCandleList(tsCode)
    val mergedIndicators = applyIndicatorPatch(current.indicators, delta.indicatorsPatch)
    _state.value = _state.value.copy(
        chartData = current.copy(
            candles = merged,
            indicators = mergedIndicators,
            snapshotVersion = delta.version
        )
    )
}
```

`CandleChartData` 增 `snapshotVersion: Long` 字段以支持 delta 合并。

### 4.3 合并算法测试

`compose-app/src/commonTest/kotlin/.../CandleDeltaMergeTest.kt`：

1. SYNC → Delta（替换最后 1 根）→ 结果与"重新 SYNC"等价
2. SYNC → Delta（baseVersion 错误）→ 应触发 re-SYNC
3. SYNC → Delta（replaceFromIndex 越界）→ 应触发 re-SYNC
4. SYNC → Delta → Delta → Delta（连续 100 次）→ candles 长度不变，最后一根值正确

## 5. 影响面

| 维度 | 影响 |
|---|---|
| 协议 | 新增 v2 payload，旧 client 继续走 v1（向后兼容）|
| 客户端合并复杂度 | 中等。必须有 baseVersion / replaceFromIndex 校验失败的 re-SYNC 路径 |
| server encodedCache | Delta 不缓存，Sync 仍缓存 |
| 测试覆盖 | 必须有合并算法单元测试 + 长跑回归测试 |
| 灰度 | client 控制 `clientProtocolVersion`；server 控制 `CANDLE_DELTA_ENABLED` 环境变量（默认 false 上线后开启）|

## 6. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Delta 合并 bug 导致 K 线数据错乱 | baseVersion 严格校验 + 越界检测 → 自动 re-SYNC |
| 网络丢帧导致 client 状态与 server 不一致 | re-SYNC 兜底（也可在 client 定时心跳带最新 version）|
| 跨周期切换或翻页 | server 检测窗口变化时强制 SYNC，不走 delta |
| 多 session 同 key 出现帧顺序问题 | session-bound lastSentMeta，与连接生命周期一致 |

## 7. 灰度与回滚

- **server 灰度**：环境变量 `CANDLE_DELTA_ENABLED`（默认 false 直到验证完成）。开启后才会走 delta 路径，否则即使 client 声明 v2 也仍返回 Sync。
- **client 灰度**：`clientProtocolVersion` 字段在 build-time 配置中可调，默认 v2，问题时改回 v1。
- **回滚**：任一端关闭灰度开关。无需紧急发版（双向独立）。

## 8. 实施前需要先做的事

- [ ] P2.A 必须先落地（delta 也要 indicators patch）
- [ ] 评估 inline sealed class 序列化在 wasm 上的行为（JsonClassDiscriminator 是否正常）
- [ ] 设计 CandleTrace 中针对 delta 的新 stage：`PROVIDER_DELTA_SENT` / `CLIENT_DELTA_APPLIED` / `CLIENT_DELTA_RESYNC_REQUESTED`
- [ ] 长跑测试方案：自动化脚本订阅一只股票 1 小时，对比终态 candles 与一次性全量 SYNC 的差异
