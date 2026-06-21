# Agent OS 沙箱安全重启方案

本文档是 agent OS 层沙箱（`sandbox-exec`）从「止血全关」状态安全重启的权威设计。配合
`docs/architecture/agent-process-lifecycle.md`、`agent-session-acp-alignment.md` 阅读。

## 背景

2026-06-20 给用户分析 agent（USER 档）与盘后回填 agent（BACKFILL 档）上 OS 层沙箱后，
重新部署，前端用户连不上 agent。线上现象：单个共享 AcpClient 上 4 次 `▶ newSession`、
0 次 `✔ session created`、`withTimeout(10_000L)` 无超时日志、3 分 47 秒不返回，最终随
shutdown 被杀。临时止血：已部署 artifact 的 `deploy.release/bin/start.sh` 在 APP_OPTS
注入 `-Dquant.agent.sandbox.disable=true`，两档沙箱全关，用户恢复连接。

## 已实证硬事实（本机实测，非推测）

1. 用真实 AcpClient（线上同一套 StdioTransport+Protocol+Client+kimi env+sandbox USER 档）
   离线跑：串行 newSession 2065ms 成功；并发 4 个 newSession（复刻线上 4 连接）4/4 成功 0 卡死；
   整条 newSession→prompt→event 流→PromptResponseEvent 成功。**沙箱 USER 档 + 完整链路本身跑得通。**
2. sandbox-exec 双向 stdio 字节守恒（JVM ProcessBuilder 300KB write+readback match），不丢帧。
3. ACP SDK 的 StdioTransport 写是单协程 writeJob 串行消费 Channel，send() 只 trySend，不可能撕裂帧。
4. 沙箱把 newSession 从 ~1.2s(OFF) 拉到 ~2-5s(ON)。
5. `withTimeout(10_000L)` 那次未触发 = 真卡点落在 withTimeout 作用域之外。
6. `configDir="~/.quant_agents/config_isolated"` 的 `~` 未被 JVM 展开 → 畸形相对路径不在沙箱
   file-write 白名单（写它 Operation not permitted），但实测该写失败不直接致 newSession 卡死。

## 根因判定（对抗式审查改判）

线上 3m47s 卡死最可能不在被 `withTimeout(10s)` 包裹、且可取消的 newSession 里
（`AgentWebSocketService.kt:667`），而在它之外的 `getOrCreateRuntime → bridge.launch →
AcpClient.initialize` 的 `client.initialize()` ACP 握手（`AcpClient.kt:237`）——这段在
`runtimeMutex.withLock` 内（`line 808`）、完全无超时覆盖。第一条连接卡在 launch，后续连接
阻塞在 runtimeMutex 上。**结论：一切「只给 newSession 串行化/加超时」若不先覆盖 launch 都打错靶。**

> 待场景 A 实验线上线下钉死：第一条连接日志显示 initialize 已成功，故卡点是否真在 launch
> 需用 fake claudeCommand 制造 launch 握手卡死复现确认；落地 step 3 前以此为准。

另两个致命点：
- `AcpClient.shutdown`（`line 339`）先 `protocol.close()`（`line 348`）再 OS kill 进程树（`line 354+`）。
  protocol.close 卡住则 OS 强杀永远到不了 → 必须把强杀前置。
- 恢复路径 `invalidateRuntime → shutdown` 跑在可能被饿死的 `Dispatchers.IO` 池且持有
  runtimeMutex → 需挪到独立专用线程，且绝不在恢复路径重入 runtimeMutex。

## 方案（最小加固 + 冷启动预热 + 可回退灰度，三者正交）

沙箱 profile（`SandboxProfile.render`/`execPrefix`）一字不改——已实测可用。加固全在调用侧与兜底层。

| # | 文件 | 位置 | 改动 |
|---|------|------|------|
| 1 | `AcpClient.kt` | `shutdown()` 339-405 | OS 强杀进程树上移到 `protocol.close()` 之前；protocol.close 包 runCatching+短超时 |
| 2 | `AgentWebSocketService.kt` | 字段区 45-50 | 新增 `recoveryScope`（`newFixedThreadPoolContext(2,"agent-recovery")`），承载收口 |
| 3 | `AgentWebSocketService.kt` | `getOrCreateRuntime` `bridge.launch` 838 | 套 `withTimeout(30s)`；超时在锁内异步 shutdown+不写表+rethrow，不重入 runtimeMutex |
| 4 | `AgentWebSocketService.kt` | createSession withTimeout 块 666-674 | 成功喂 guard.recordSuccess；超时分支挪 recoveryScope+guard.recordTimeout；修文案 30s→10s |
| 5 | `SandboxRolloutGuard.kt` | 新建（org.shiroumi.agent.acp） | per-tier ARMED/TRIPPED 状态机 + 无锁滑窗；TRIP 后 effectiveTier 返回 OFF；ops 热钩子 |
| 6 | `SandboxProfile.kt` | `isEnabled` 63-75 | OFF 短路后、disable 检查前插 `if (guard.effectiveTier(tier)==OFF) return false` |
| 7 | `AcpClient.kt` | initialize configDir 190-196 | configDir 的 `~/` 展开为 `$HOME/`，消除 file-write deny 噪声 |
| 8 | `AgentEntryPriceAnalyzer.kt` | launch 77 / createSession 95 | BACKFILL 档套 withTimeoutOrNull(30s/10s)+guard 探针，单票卡死有界 |
| 9 | `AgentWebSocketService.kt` + `Main.kt` | 新增 warmup() + 旁路 launch | USER 档冷启动预热，合成 UUID+`__warmup__` workDir，不进任何注册表，跑完 shutdown |
| 10 | `AgentEntryBackfillStep.kt` + `StrategyServiceMain.kt` | 新增 warmup() + 旁路 launch | BACKFILL 档冷启动预热（独立 JVM 必须各自预热）。**暂不落地**：收益弱（回填常在启动数小时后触发，预热进程早被杀），BACKFILL 核心保护是已落地的 step 8 硬超时；待 USER 档线上验证收益后按需补 |
| 11 | `InternalCliRoute.kt` | `/api/internal/cli/sandbox/rollout`（GET/trip/rearm） | 运维秒退/重武装/观测，复用既有 `/api/internal/cli` loopback intercept（commit 03d3b83 同源） |

**串行化（per-bridge newSession Mutex）默认不上线**：S2 已证 4 并发 newSession 不互卡，若卡点在
launch 则串行化是 no-op/错药且把并行降为串行违反 OFF 态等价承诺。仅当线上复现确认并发 newSession
争用才启用，且必须持锁段有硬超时+超时释放锁+OS 强杀。

## 链路一致性保证（非故障路径与当前行为等价）

- guard 健康态（ARMED）恒等返回 requested → `isEnabled` 收到的 tier 与今天一字不差 →
  `AcpClient.kt:154` sandboxOn、`line 155` sandboxPrefix、`line 179` env 收窄三者全字节等价；
  render/execPrefix 一字未改，`.sb` profile 文本逐字节相同。
- launch 30s / newSession 10s 的 withTimeout 在正常路径（launch 2-5s、newSession 2065ms）均不触发，
  不进 catch、不调 recoveryScope → 与当前逐字等价；仅真卡死时新增收口动作。
- shutdown 收口顺序调整只改「卡死收口路径」可靠性；正常 shutdown 最终态相同。
- warmup 用合成 UUID + `__warmup__` workDir，完全不进 runtimesByUserId/runtimeMutex/activeSessions，
  onProcessStarted=null 不污染 AgentProcessRegistry，跑完即 shutdown → 真实路径观察到的状态不变。
- 两处守卫（isEnabled(USER)/isEnabled(BACKFILL)+cfg.enabled）：当前 disable=true 止血态下
  warmup 与 guard 全不参与，OFF 态彻底零侵入。

## 重启操作序列（逐条可执行）

前置：验收场景全绿（已完成：场景 A/A2 根因实证、guard 6 单测、profile 字节等价、沙箱隔离边界 11 测、
真实链路 S1/S2/S3 跑通）。

**阶段 0 — 部署新代码但保持沙箱仍关（验 OFF 态零回归）**
```bash
# committed src start.sh 不带 disable flag,出包后需手动注入(权威位置=已部署 artifact)
./deploy.sh release
# 出包后立即给两个启动脚本的 APP_OPTS 注入 disable(用 Edit 改 deploy.release/bin 下的脚本):
#   ktor-server/build/deploy.release/bin/start.sh                APP_OPTS 加 -Dquant.agent.sandbox.disable=true
#   ktor-server/build/deploy.release/bin/start-strategy-service.sh  APP_OPTS 加 -Dquant.agent.sandbox.disable=true
# 然后 deploy.sh 内部会拉起两进程(或手动 start)。验证:用户能连、回填正常,与当前止血态等价。
```

**阶段 1 — USER 档先开（低峰期，避开盘后回填窗口）**
```bash
ktor-server/build/deploy.release/bin/start.sh stop
# Edit 移除 start.sh APP_OPTS 里的 -Dquant.agent.sandbox.disable=true
ktor-server/build/deploy.release/bin/start.sh start
tail -f ktor-server/build/deploy.release/logs/server.log   # 期望: sandbox=ON (tier=USER) + warmup 日志
```
观测：前端真实账号建会话跑一次买卖点分析；`▶newSession→✔ session created` 配对、无 3m47s 卡死、
`CLAUDE_CONFIG_DIR` 落真实绝对路径无 file-write deny。

**阶段 2 — 观测窗口（建议一个交易日）**
```bash
curl -s http://127.0.0.1:9870/api/internal/cli/sandbox/rollout   # state 应稳定 ARMED、timeout 低位
```

**阶段 3 — BACKFILL 档开（USER 稳定后，盘后窗口前）**
```bash
ktor-server/build/deploy.release/bin/start-strategy-service.sh stop
# Edit 移除 start-strategy-service.sh APP_OPTS 里的 -Dquant.agent.sandbox.disable=true
ktor-server/build/deploy.release/bin/start-strategy-service.sh start
```
观测：一次真实盘后回填覆盖率回 1.0、`advanceHoldings` 不被阻断。

**任一档 guard 自动 TRIP 或人工观测异常** → ops trip 秒退定位（见回退优先级），必要时回退。

## 回退优先级

1. **秒退（不重启）**：`POST /api/ops/sandbox/rollout/trip?tier=USER` → guard effectiveTier 改 OFF。
2. **止血回退（重启）**：在**已部署 artifact** 的 start.sh 重新注入 `-Dquant.agent.sandbox.disable=true`
   重启。⚠️ committed `src/main/scripts/start.sh` **不带**该参数，下次 deploy 从模板重写会丢失 →
   runbook 必须写死「flag 权威位置=已部署 artifact，重部署后需重新注入」。
3. **代码回退**：git revert 本方案文件，重新出包。关沙箱（disable）只关 sandbox-exec 维度，
   recoveryScope/launch 硬超时/guard/warmup/shutdown 顺序调整在 OFF 态本就不参与，无害，无需撤。

## ⚠️ 当前线上止血态的一致性缺口（2026-06-21 发现）

止血 `-Dquant.agent.sandbox.disable=true` 当初**只加到了 ktor-server**（USER 档，`deploy.release/bin/start.sh:62`）。
**strategy-service（BACKFILL 档，`start-strategy-service.sh`）漏加** → 当前 BACKFILL 档沙箱按 isEnabled 默认**开着**
（本机有 sandbox-exec），且运行的 11538 是**本批改动前的旧 jar**（无 step 8 launch 硬超时保护）。
`entryBackfill.enabled=true`。

风险：下次盘后回填若撞 launch 握手卡死，回填会卡（旧 jar 无 step 8 兜底）。当前非盘后窗口、风险未触发。

待用户决策：是否在下次盘后窗口前给 `start-strategy-service.sh` APP_OPTS 也注入 disable flag 并重启
strategy-service（重启是 outward-facing 操作，需用户确认），使两档止血态一致；或直接走完整重启序列上新代码。

## 残留不确定性与观测兜底

- 根因未用线上 jstack 100% 钉死 → 上线前跑场景 A 复现确认卡点在 launch；上线后在
  launch/initialize 前后加时间戳日志，下次卡死凭日志定位。
- 子进程侧 vs JVM 侧无法区分 → step 1 OS 强杀前置使两种情形都被秒级收口，不依赖区分；
  建议线上对 claude 子进程 stderr 做 INHERIT 落盘留证。
- BACKFILL warmup 收益弱于 USER（回填可能启动数小时后触发，预热进程已被杀）→ 场景 H 实测幅度。
- guard TRIP→OFF 后沙箱不设防（回到事故面）= 两害相权取可用性 → onTrip 打 ERROR + 告警，rearm 走人工 ops。
