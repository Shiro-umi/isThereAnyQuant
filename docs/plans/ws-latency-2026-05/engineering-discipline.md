# 工程纪律

> 本次延迟治理的跨阶段约束。任何阶段的实施都必须遵守这些原则，否则不应 merge。

## 1. PR 拆分原则

- **P0 三项一个 PR**（compression + connectionState + watchdog），可以分 3 个 commit 便于 review。
- **P2.A 独立 PR**，P2.B 独立 PR，且 P2.B 依赖 P2.A 已 merge 到 main。
- 禁止把 P0 / P2 合并到一个 PR——一旦 indicator 算法或 delta 合并出问题，会拖累零风险的压缩改动一起 revert。

## 2. 灰度与回滚要求

每个 PR 必须满足：

- **零侵入回滚**：任何阶段都能在不发版的前提下"立即关闭"。
  - P0.1 压缩：删除服务端 extensions 块即停止
  - P0.2 connectionState：删除 UI 文案分支，state 字段保留但不被消费
  - P0.3 watchdog：删除 watchdog 启动调用即可
  - P2.A 服务端指标：环境变量 `CANDLE_INDICATORS_SERVERSIDE_ENABLED=false`
  - P2.B delta：环境变量 `CANDLE_DELTA_ENABLED=false`
- **协议字段向后兼容**：新增字段必须 nullable + 有 default value。前后端 `ignoreUnknownKeys=true` + `encodeDefaults=true` 已就位（[AppWebSocketConnectionManager.kt:90](../../../ktor-server/src/main/kotlin/org/shiroumi/server/websocket/AppWebSocketConnectionManager.kt) / [GlobalWebSocketClient.kt:99](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/service/GlobalWebSocketClient.kt)）。

## 3. 禁止顺手改动的范围

实施过程中**严格禁止**：

- 改 `convergenceKey` / `commandSeq` 设计。当前 [AppWebSocketConnectionManager.kt:514-563](../../../ktor-server/src/main/kotlin/org/shiroumi/server/websocket/AppWebSocketConnectionManager.kt) 的收敛域逻辑经过仔细推演，本批次任何子项都不应触碰。
- 改 [isRestorableStateCommand](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/service/GlobalWebSocketClient.kt:462-471) 离线丢弃策略。该策略本身正确，P0.2 已通过 UI 反馈解决用户感知。
- 顺带改 `setStockListContext` 加 owner 隔离。属于 P1 防御性改动，不在本批次。
- 在性能问题主链路之外做"看起来更优雅"的小重构。

## 4. 验证基线

实施每个阶段前后都要采集这些指标做对比：

| 指标 | 采集方式 | 基线场景 |
|---|---|---|
| 公网首屏 K 线显示耗时 | 浏览器手动计时（用户点股票 → K 线渲染完成）| release 部署后，bigsmart.space 域名访问 |
| WS 帧字节数 | DevTools → Network → WS → frame size | SUBSCRIBE_CANDLE 后第一个 CANDLE_DATA SYNC 帧 |
| 主线程切股阻塞时间 | DevTools Performance trace | 切换股票动作中 computeChartData 调用 |
| 切股 → 数据到达 server 端耗时 | CandleTrace `WS_COMMAND_RECEIVED` → `PROVIDER_SEND_COMPLETE` | 公网用户实测 |

建议建一个临时 `temp/ws-latency-baseline.md` 持续追加 P0 前 / P0 后 / P2.A 后 / P2.B 后的四组数据，作为治理收益的对外说明依据。

## 5. 调试期日志策略

- 实施期可以临时加 println / debugLog，但 merge 前必须用 `AppConfig.testMode` 包裹（参考 [CandleViewModel.candleDebugLog](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/feature/candle/presentation/CandleViewModel.kt:38-40)）。
- server 端日志用 `logger.info / warning`，避免误用 `error` 级别打非错误事件。
- P0.2 connectionState 状态切换可以走 `logger.info`，频率低不会刷屏。

## 6. PR 描述模板

```
## 背景
（一句话引用 docs/plans/ws-latency-2026-05/README.md，说明本 PR 属于哪个阶段）

## 改动
- [ ] 子项 1：xxx
- [ ] 子项 2：xxx

## 影响面
（按 plan 中"影响面"小节复制，列出协议 / 缓存 / 兼容性 / 性能 等维度）

## 验证
- [ ] 本地 debug 跑通
- [ ] debug-wan 公网域名跑通
- [ ] release 部署后采集指标对比基线

## 回滚
（按 plan 中"回滚"小节复制）

## 关联
- Plan: docs/plans/ws-latency-2026-05/<对应文档>.md
```

## 7. release 前自检 checklist

每个 PR merge 到 main 后、release 部署前：

- [ ] 本地 `./gradlew build` 通过
- [ ] `./deploy.sh debug-wan` 跑一次完整流程，浏览器访问 bigsmart.ddns.net:9871 验证基础功能
- [ ] CandleTrace 日志无异常 stage
- [ ] 浏览器 DevTools Network → WS 看到帧大小符合预期
- [ ] 切股 / 切周期 / 离线重连场景手动覆盖

## 8. 不在本批次但需要后续治理的事项

记录下来，避免后续遗忘：

- **P1**：`setStockListContext` 缺 owner 隔离。当前只行情页一个消费者无 bug；将来 STOCK_LIST_UPDATE 多页订阅时必须先治理。位置 [GlobalWebSocketClient.kt:646-653](../../../compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/service/GlobalWebSocketClient.kt)。
- **P3 可观测性**：CandleTrace 报表化、公网 RTT 拨点监控。
- **网络层架构**：评估 Cloudflare Tunnel 替换为直连 / WireGuard / 自建反代。属于运维/SRE 范畴，超出本治理范围。
