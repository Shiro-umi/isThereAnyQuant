# WebSocket 公网延迟与 K 线订阅卡死治理 Plan

> 立项日期：2026-05-19
> 触发问题：release 部署到公网后访问延迟显著高；快速切换股票 / 页面时 K 线订阅长时间无返回。
> 影响链路：`浏览器 → Cloudflare Tunnel → /ws/app-stream → AppWebSocketConnectionManager → CandleSubscriptionService → strategy-service snapshot ↩︎ GlobalWebSocketClient → CandleViewModel`

## 1. 背景

release 配置（[config.yaml:23-29](../../../config.yaml)）使前端 `wsBaseUrl = wss://bigsmart.space`，强制走 Cloudflare Tunnel。结合 review 三个角度的结论：

- **网络传输层（主因）**：Cloudflare 隧道 + 无 WebSocket 压缩 + 100–200 KB 全量 K 线 JSON，公网首屏延迟 600–1500ms 完全合理。
- **协议与命令收敛（次要）**：convergenceKey/commandSeq 机制本身正确。真正影响"切股长时间不返回"的是断线期间 `isRestorableStateCommand` 默默丢弃命令，UI 缺反馈。
- **前端订阅生命周期（局部隐患）**：CandleViewModel 写得很谨慎，但缺 watchdog；`setStockListContext` 无 owner 隔离是未来隐患。

详细分析参考本次 review 的对话产物（未单独成文，结论已蒸馏到本目录文档）。

## 2. 总览

| 阶段 | 内容 | 工作量 | 风险 | 节奏 |
|---|---|---|---|---|
| **P0** | WS permessage-deflate + connectionState 暴露 + Candle 切股 8s watchdog | 0.5–1 人天 | 低 | 一个 PR |
| **P2.A** | 指标计算下沉到 server（CANDLE_DATA payload 内嵌 indicators） | 2–3 人天 | 中 | P0 上线观察一周后 |
| **P2.B** | CANDLE_DATA delta 推送 | 3–5 人天 | 中高 | 独立 PR，需协议版本协商 |

P1（owner 隔离防御性改动）当前不纳入本批次，等出现实际多页订阅 `STOCK_LIST_UPDATE` 时再做。

## 3. 文档索引

- [P0 一揽子修复](P0-quick-wins.md) — 压缩 + 连接状态 + 切股 watchdog
- [P2.A 指标下沉](P2A-server-indicators.md) — 主线程性能优化
- [P2.B Delta 推送](P2B-candle-delta.md) — 带宽优化
- [工程纪律](engineering-discipline.md) — 跨阶段约束、回滚原则、可观测性预留

## 4. 不在本 Plan 范围

- **Cloudflare Tunnel 替换 / WireGuard / 自建反代**：架构级改动，超出 review 范围。
- **修改 commandSeq drop 策略让离线命令排队**：drop 策略本身是对的，P0 已通过 UI 反馈 + watchdog 解决用户感知。
- **CandleViewModel 重构 / Repository 抽象**：不在性能问题主链路上。

## 5. 验收口径

- **P0 验收**：
  - release 部署后 DevTools Network 显示 `Sec-WebSocket-Extensions: permessage-deflate` 协商成功（如 wasm 不支持则服务端日志显示 fallback）。
  - CANDLE_DATA 首帧实际传输字节较修改前下降 60%+。
  - 切股期间断网，UI 显示"网络重连中…"；恢复网络后 K 线自动 hydrate。
  - 8s 内无任何响应时 UI 显示超时错误 + 可点击重试。
- **P2.A 验收**：切股动作 `computeChartData` 主线程调用时长从 80–150ms 降到 < 5ms。
- **P2.B 验收**：盘中实时推送 5 分钟，总传输字节较 v1 下降 90%+。
