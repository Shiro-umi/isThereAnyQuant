# P2.A：指标计算下沉到 server

> 目标：把 EMA/MA/RSI/MACD/BOLL 五种指标的计算从前端主线程移到 server JVM。
> 节省切股主线程 80–150ms 阻塞，叠加 P0.1 网络压缩后整体首屏感知应从 1–2s 降到 < 500ms。
> 工作量 2–3 人天，独立 PR，灰度环境变量控制。

## 1. 业务链路定位

```
server: CandleSnapshotManager → CandleDataFacade → CandleProjectionService
        → CandleDataProvider.sendSnapshot → composePayloadJson
client: handleGlobalEvent(CANDLE_DATA) → _candleEventsFlow → CandleViewModel.collectLatest
        → computeChartData (toCandleChartDataYielding: EMA/MA/RSI/MACD/BOLL)
        → state.chartData
```

当前痛点：
- 客户端 [CandleViewModel.computeChartData](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt:757-761) → [toCandleChartDataYielding](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/contract) 是 5 个指标算法 + 500 根点位的 O(n) 循环，wasm 主线程 80–150ms。
- LRU `chartDataCache`（[CandleViewModel.kt:141](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt) maxSize=16）在切股场景命中率接近 0。
- server JVM 上同样算法 5–10ms，且 `encodedCache`（[CandleDataProvider.kt:59](../../../ktor-server/src/main/kotlin/org/shiroumi/server/data/provider/CandleDataProvider.kt)）能让多用户共享。

## 2. 协议扩展

### 2.1 shared model

`shared/src/commonMain/kotlin/model/ws/CandleData.kt`（具体路径以现有 CandleDataPayload 定义为准）：

```kotlin
@Serializable
data class CandleDataPayload(
    val tsCode: String,
    val candles: List<CandleData>,
    val totalCount: Int,
    val requestParams: CandleSubscribeRequest? = null,
    val indicators: CandleIndicatorBundle? = null,   // 新增，可选向后兼容
)

@Serializable
data class CandleIndicatorBundle(
    /** key = 周期参数（如 5, 10, 20, 60） */
    val ema: Map<Int, List<Float?>>,
    val ma: Map<Int, List<Float?>>,
    val rsi: Map<Int, List<Float?>>,
    val macd: MacdSeries,
    val boll: BollSeries
)

@Serializable
data class MacdSeries(
    val dif: List<Float?>,
    val dea: List<Float?>,
    val macd: List<Float?>
)

@Serializable
data class BollSeries(
    val upper: List<Float?>,
    val middle: List<Float?>,
    val lower: List<Float?>
)
```

**字段对齐**：所有 List 长度必须等于 `candles.size`，前 N 个无效点用 null。

### 2.2 灰度开关

`ktor-server` 启动读取环境变量 `CANDLE_INDICATORS_SERVERSIDE_ENABLED`（默认 `true`）：

```kotlin
// CandleProjectionService 或 DataProviderBootstrap 单点读取
val serverIndicatorsEnabled = System.getenv("CANDLE_INDICATORS_SERVERSIDE_ENABLED")
    ?.equals("false", ignoreCase = true)?.not() ?: true
```

`false` 时 server 仍输出 `indicators = null`，客户端走 legacy 路径。

## 3. 算法迁移

### 3.1 抽取到 shared 模块

把前端的指标算法（在 `compose-app/src/commonMain/.../toCandleChartDataYielding` 中）抽到：

```
shared/src/commonMain/kotlin/model/candle/indicator/
├── EmaCalculator.kt
├── MaCalculator.kt
├── RsiCalculator.kt
├── MacdCalculator.kt
└── BollCalculator.kt
```

每个 calculator：
- 入参：`List<Float>`（close 序列）+ 周期参数
- 出参：`List<Float?>`（长度等于入参，前 N-1 个为 null）
- 纯函数，无 Compose / yield 依赖
- 单一职责，便于单元测试

**前端原本的 yield 让出主线程逻辑不再需要**（指标已在 server 算完）。`toCandleChartDataYielding` 只保留"把 indicators 装进 CandleChartData"的纯组装逻辑，不再做计算。

### 3.2 server 侧调用点

[CandleProjectionService](../../../ktor-server/src/main/kotlin/org/shiroumi/server/data/subscription/) 的 `project()` 返回 `CandleDataPayload` 前组装 indicators：

```kotlin
fun project(key: CandleKey, request: CandleSubscribeRequest, snapshot: CandleSnapshotState): CandleDataPayload {
    val candles = /* 已有逻辑 */
    val indicators = if (serverIndicatorsEnabled) {
        val closes = candles.map { (it.adjClose ?: it.close).toFloat() }
        CandleIndicatorBundle(
            ema = listOf(5, 10, 20, 60).associateWith { EmaCalculator.compute(closes, it) },
            ma = listOf(5, 10, 20, 60).associateWith { MaCalculator.compute(closes, it) },
            rsi = listOf(6, 12, 24).associateWith { RsiCalculator.compute(closes, it) },
            macd = MacdCalculator.compute(closes, fast = 12, slow = 26, signal = 9),
            boll = BollCalculator.compute(closes, period = 20, stdDev = 2.0f)
        )
    } else null
    return CandleDataPayload(/* ... */, indicators = indicators)
}
```

### 3.3 server encodedCache 扩展

[CandleDataProvider.encodedCache](../../../ktor-server/src/main/kotlin/org/shiroumi/server/data/provider/CandleDataProvider.kt:59) 当前缓存的是 candles JSON。扩展为同时缓存 indicators JSON：

```kotlin
private data class EncodedSnapshot(
    val version: Long,
    val totalCount: Int,
    val candlesJson: String,
    val indicatorsJson: String?   // null when serverIndicatorsEnabled=false
)
```

`composePayloadJson` 同时拼入 candles 与 indicators 字段。`requestSignature()` 不变（indicator 参数当前是硬编码 5/10/20/60，无需进入 signature）。如果未来 indicator 参数可配置，需要进入 signature。

### 3.4 前端调用点

[CandleViewModel.kt:386-388](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt) 区域：

```kotlin
val chartData = chartDataCache[cacheKey] ?: run {
    val data = if (payload.indicators != null) {
        // 新路径：直接组装，跳过本地指标计算
        candles.toCandleChartDataWithIndicators(
            tsCode = stock.code,
            name = stock.name,
            indicators = payload.indicators
        )
    } else {
        // legacy 路径：本地计算
        computeChartData(candles)
    }
    data?.also { chartDataCache[cacheKey] = it }
}
```

`toCandleChartDataWithIndicators` 是新增的 pure 组装函数，与 `toCandleChartDataYielding` 共享数据结构。

## 4. 算法一致性测试

**这是 P2.A 的最大风险点。** 前端原算法与新 shared calculators 必须逐点一致。

### 4.1 测试样本

在 `shared/src/commonTest/kotlin/model/candle/indicator/IndicatorParityTest.kt` 写测试：

- 取 3 只代表股票（高波动 / 低波动 / 停牌过的）500 根日 K close 序列作为 fixture
- 对每个 calculator 跑新旧两版，逐点比对，精度容忍 1e-4
- 边界用例：长度 < N 的输入、全相同值、单调递增、含 NaN（停牌）

### 4.2 集成验证

server release 部署后，前端关闭 server indicators（`?dev_disable_server_indicators=1` 之类的 query 开关或本地 build flag），切换同一只股票：
- 启用 server indicators 时的图表
- 禁用（fallback 本地计算）时的图表
- 两次截图肉眼比对

## 5. 影响面

| 维度 | 影响 |
|---|---|
| payload 体积 | 5 个 indicators × ~500 个 float = ~25 KB 增量；deflate 后 < 5 KB |
| server CPU | encodedCache 命中时零增量；miss 时单次 5–10ms |
| server 内存 | encodedCache value 增加 ~25 KB / entry，当前 cache 无上限——**P2.A 顺带给 cache 加 LRU 上限**（如 maxSize = 256）|
| 前端主线程 | computeChartData 从 80–150ms 降到 < 5ms |
| 向后兼容 | indicators 字段 nullable，旧客户端可 ignore；新客户端检查 null 时 fallback 本地计算 |

## 6. 验证

1. **算法一致性**：单元测试覆盖 5 个指标。
2. **性能**：DevTools Performance trace 切股，computeChartData 调用时长。
3. **payload 体积**：DevTools Network → WS frame size，对比 P0.1 实施后 / P2.A 实施后两次基线。
4. **回归**：盘中实时 UPDATE 推送场景 indicators 也要正确更新（不能只 SYNC 时正确）。

## 7. 灰度与回滚

- **灰度**：环境变量 `CANDLE_INDICATORS_SERVERSIDE_ENABLED` 默认 true，问题时设 false 立即回退。
- **回滚**：协议字段 nullable，客户端永远兼容；server 端关闭灰度开关即可。无需 release 紧急发版。

## 8. 实施前需要先做的事

- [ ] 确认前端 toCandleChartDataYielding 的实际算法实现位置（grep）
- [ ] 确认指标周期常数（5/10/20/60 等）当前是不是硬编码，是否要进入 requestSignature
- [ ] 确认 encodedCache 当前是否已有 LRU 上限，本次顺便补上
