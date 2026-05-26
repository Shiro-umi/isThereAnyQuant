# Agent Session 与 ACP 会话对齐规划

> 目标：让前端 Agent 面板的新建对话、切换会话、停止、断线重连、继续对话语义，与 Claude Code 原生 ACP Session 尽量一致，避免前端生命周期结束后丢失后端 Claude 会话上下文。

## 1. 结论

仅仅在前端加一个“Session”概念，不足以解决“停止后继续不了之前任务”的问题。当前问题的根因是：

1. 前端 `AgentViewModel.onCleared()` 会调用 `disconnect()`，进而 `unsubscribeAgent()` + `closeAgentSession()`。
2. 后端 `AGENT_CLOSE_SESSION` 会关闭 `AgentBridgeImpl` 和 `AcpClient`，Claude 进程与 ACP `ClientSession` 被销毁。
3. `GlobalWebSocketClient` 只维护一个 `activeAgentSessionId`，没有多会话目录，也没有“重新订阅已有后端会话”的产品语义。
4. `AGENT_STOP` 目前是对同一个 ACP Session 发送 interrupt，理论上可以保留会话继续对话；但如果前端停用导致 close session，继续就只能落到新进程、新 ACP Session。

因此正确方向是：把“前端会话”提升为业务会话目录，并且让每个前端 Agent Session 一一绑定一个后端 ACP `ClientSession`。前端关闭面板、页面销毁、WebSocket 断线只表示“取消订阅状态流”，不表示关闭 Claude 会话。只有用户明确关闭/删除会话，才销毁后端 ACP Session。

## 2. 已触发的项目 Skill

本规划涉及的链路与已触发 Skill：

| Skill | 触发原因 | 使用结论 |
|---|---|---|
| `.claude/skills/agent-architect` | 涉及 Agent、ACP、会话管理、StateManager、进程生命周期 | 以 `AgentViewModel -> GlobalWebSocketClient -> AgentWebSocketService -> AgentBridgeImpl -> AcpClient` 为主链路设计 |
| `.claude/skills/socket-protocol` | 涉及 `/ws/app-stream`、`AGENT_SESSION`、`AGENT_STREAM`、CommandType/Payload 扩展 | 新增命令必须兼容 `commandSeq` 与订阅恢复，`SYNC/UPDATE` 仍推完整可消费视图 |
| `.claude/skills/compose-ui-architect` | 涉及前端 Agent 面板新增按钮、会话列表、MVI 状态 | 改动顺序按 Contract -> ViewModel -> UI -> Service，优先 Web commonMain |
| `.claude/skills/data-layer-architect` | 涉及共享 WS 模型、可选数据库表、前端数据访问层 | 若落库会话目录，需要同步 common_db 表、Repository、REST/WS 模型文档 |

不触发个股分析类 `agent/analysis-skills/*`，因为本次不是让 Agent 分析股票行情，而是设计 Agent 交互链路。

## 3. 真实业务流程

### 3.1 用户正在分析股票时停止前端

当前链路：

```text
用户在行情页选中股票
  -> AgentSidebar 发送分析 Prompt
  -> AgentViewModel 乐观添加 user/assistant 消息
  -> GlobalWebSocketClient.sendAgentPrompt(sessionId, prompt, context)
  -> AgentWebSocketService.sendPrompt()
  -> AgentBridgeImpl.handlePrompt()
  -> AcpClient.prompt(acpSessionId, message)
  -> StateManager 流式更新
  -> AGENT_STREAM 推送到前端
```

异常点：

```text
前端页面/面板停止或 ViewModel 销毁
  -> AgentViewModel.onCleared()
  -> disconnect()
  -> GlobalWebSocketClient.closeAgentSession(sessionId)
  -> AgentWebSocketService.closeSession(sessionId)
  -> bridge.shutdown()
  -> acpClient.shutdown()
  -> Claude 进程与 ACP Session 销毁
```

用户随后输入“继续”时，前端通常已经创建新后端 session。Claude 没有原来的 conversation context，自然无法继续。

### 3.2 用户点击“停止”后继续

合理语义应拆成两种：

| 用户动作 | 业务语义 | 后端动作 |
|---|---|---|
| 停止当前回答 | 类似 Claude Code interrupt，保留对话上下文 | `AGENT_STOP` -> `AgentBridge.Command.Interrupt`，不关闭 ACP Session |
| 新建对话 | 类似 Claude Code new session，新上下文 | 创建新的业务 Session + 新 ACP `ClientSession` |
| 关闭/删除会话 | 用户明确不要这个会话 | `AGENT_CLOSE_SESSION` -> shutdown Claude 进程/进程树 |
| 前端离开/断线 | UI 不再订阅流，不代表删除会话 | `UNSUBSCRIBE_AGENT`，后端保留运行会话并延迟清理 |

“继续”只能保证在同一个 ACP Session 内有效。对已经被 interrupt 的半截推理，Claude 能否逐 token 续写原回答取决于底层 Claude Code 对取消轮次的记录方式；但至少应保留同一个对话上下文，让用户说“继续刚才的分析”时能基于前文、工具记录和已输出内容继续推进。

## 4. 目标语义

### 4.1 前端 Agent Session

前端 Agent 面板新增“新建对话 Session”按钮，语义与 Claude Code 原生对齐：

- 点击“新建对话”：创建一个全新的 Agent 会话，不继承旧对话上下文。
- 当前会话仍在后台保留，可从会话列表切回。
- 切换会话只是订阅另一个 `sessionId` 的 `AGENT_STREAM`。
- 输入框永远发送到当前选中的 `sessionId`。
- 页面刷新/重连后恢复最近选中的会话，并重新订阅。

### 4.2 后端 ACP Session

每个业务 `agentSessionId` 绑定一个 ACP `ClientSession.sessionId.value`：

```text
agentSessionId(业务ID，前后端协议 targetId)
  -> userId
  -> workDir
  -> AgentBridgeImpl
  -> AcpClient
  -> acpSessionId(Claude Code 原生 session id)
  -> Claude 进程
```

原则：

- `agentSessionId` 是前后端稳定业务 ID。
- `acpSessionId` 是底层 Claude Code 协议 ID，用于调试、追踪、对齐原生语义。
- 前端不直接使用 `acpSessionId` 作为路由主键，避免将底层协议细节暴露给 UI。
- 后端 `AgentStatePayload` 可以新增 `acpSessionId`、`title`、`createdAt`、`updatedAt`、`isDetached` 等只读字段，保持向后兼容。

## 5. 协议设计

### 5.1 新增共享模型

建议在 `shared/src/commonMain/kotlin/model/ws/AppWebSocket.kt` 增加：

```kotlin
@Serializable
data class AgentSessionSummaryPayload(
    val sessions: List<AgentSessionSummary>
)

@Serializable
data class AgentSessionSummary(
    val sessionId: String,
    val acpSessionId: String? = null,
    val title: String? = null,
    val status: AgentStatus = AgentStatus.IDLE,
    val lastOutputPreview: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val activePrompt: Boolean = false
)
```

### 5.2 新增或调整 CommandType

当前命令已有：

- `AGENT_CREATE_SESSION`
- `AGENT_CLOSE_SESSION`
- `SUBSCRIBE_AGENT`
- `UNSUBSCRIBE_AGENT`
- `AGENT_SEND_PROMPT`
- `AGENT_STOP`

建议新增：

| CommandType | targetId | payload | 语义 |
|---|---|---|---|
| `AGENT_LIST_SESSIONS` | null | null | 拉取当前用户保留中的 Agent 会话目录 |
| `AGENT_SELECT_SESSION` | sessionId | null | 选中已有会话；后端可返回该会话最新 `AgentStatePayload` |
| `AGENT_RENAME_SESSION` | sessionId | title | 修改会话标题 |

也可以不新增 `AGENT_SELECT_SESSION`，用 `SUBSCRIBE_AGENT(sessionId)` 承担“选中并订阅”的语义。推荐保留 `SUBSCRIBE_AGENT`，避免协议膨胀；新增 `AGENT_LIST_SESSIONS` 足够支撑会话目录。

### 5.3 AGENT_SESSION 事件语义

`AGENT_SESSION` 不再只表示创建成功，还承担目录同步：

| action | targetId | payload | 语义 |
|---|---|---|---|
| `SYNC` | sessionId | `AgentSessionSummary` 或 sessionId | 单个会话创建/选中成功 |
| `UPDATE` | null | `AgentSessionSummaryPayload` | 会话目录变更 |
| `ERROR` | sessionId | error message | 会话不存在/无权限/创建失败 |

为了兼容现有前端，第一阶段可以继续让 `SYNC.payload=sessionId`，新增目录同步用 `UPDATE`。

## 6. 后端落地设计

### 6.1 AgentWebSocketService 新增会话目录

当前内存状态：

```kotlin
activeSessions: sessionId -> AgentBridgeImpl
sessionContexts: sessionId -> SessionContext
lastSessionStatus: sessionId -> AgentStatus
promptContextQueue: sessionId -> ArrayDeque<SessionContext>
cleanupJobs: sessionId -> Job
```

建议新增：

```kotlin
data class AgentRuntimeSession(
    val sessionId: String,
    val userId: UUID,
    val workDir: String,
    val acpSessionId: String?,
    val bridge: AgentBridgeImpl,
    val createdAt: Long,
    @Volatile var updatedAt: Long,
    @Volatile var title: String?,
    @Volatile var lastState: ClaudeState
)
```

并将 `activeSessions` 改为：

```kotlin
activeSessions: sessionId -> AgentRuntimeSession
sessionsByUserId: userId -> MutableSet<sessionId>
```

这样 `AGENT_LIST_SESSIONS` 可以只返回当前用户会话，避免跨用户泄露。

### 6.2 不再由前端生命周期关闭 ACP Session

必须调整：

- `AgentViewModel.onCleared()` 不再调用 `closeAgentSession()`。
- 前端页面离开只发 `UNSUBSCRIBE_AGENT`。
- `AGENT_CLOSE_SESSION` 只由用户点击“关闭/删除会话”触发。
- `AgentWebSocketService.onSubscriberLeft()` 仍保留延迟清理，但对正在运行任务继续延期。

建议把 `CLEANUP_DELAY_MS` 从固定 3 分钟拆成两类：

| 状态 | 清理策略 |
|---|---|
| THINKING/EXECUTING/AWAITING_APPROVAL | 不自动关闭，只标记 detached；可设置最长运行保护，例如 30 分钟 |
| IDLE/COMPLETED/ERROR | 无订阅者后延迟关闭，例如 30 分钟或按配置 |

如果目标是完全对齐 Claude Code 的多会话体验，短期内不要自动关闭最近会话，至少保留到用户显式关闭或服务重启。

### 6.3 断线重连与重新订阅

`GlobalWebSocketClient.restoreSubscriptions()` 当前只恢复一个 `activeAgentSessionId`。改为：

```text
重连成功
  -> AGENT_LIST_SESSIONS
  -> 前端恢复 selectedSessionId
  -> SUBSCRIBE_AGENT(selectedSessionId)
  -> 后端立刻推送最新 AgentStatePayload(SYNC)
```

后端 `onSubscriberJoined(sessionId)` 应主动发送该 session 的 `lastState`，而不是只取消 cleanup job。否则重连后要等下一次 StateFlow 变化才会看到画面。

### 6.4 Stop/Interrupt 后继续

保持 `AGENT_STOP` 不关闭 session：

```text
AGENT_STOP
  -> AgentBridgeImpl.interrupt()
  -> currentPromptJob.cancel()
  -> StateManager.interrupt()
  -> AcpClient.interrupt(SIGINT)
  -> session.status = COMPLETED 或 INTERRUPTED
```

建议新增 `AgentStatus.INTERRUPTED`，语义比把中断伪装成 `COMPLETED` 更准确。若担心前端兼容，第一阶段可不新增枚举，只在 `AgentStatePayload.error/context` 外新增 `interrupted: Boolean = false`。但长期建议新增终态：

- `COMPLETED`：模型正常结束，可持久化报告。
- `INTERRUPTED`：用户主动中断，不自动落 `agent_analysis_result`。
- `ERROR`：异常失败。

持久化逻辑也应相应调整：只有 `COMPLETED && output.isNotBlank() && !interrupted` 才写分析结果。

## 7. 前端落地设计

### 7.1 State/Contract

`AgentContract.State` 新增：

```kotlin
val sessions: List<AgentSessionSummary> = emptyList()
val selectedSessionId: String? = null
val isSessionListLoading: Boolean = false
```

`Action` 新增：

```kotlin
data object LoadSessions : Action
data object NewSession : Action
data class SelectSession(val sessionId: String) : Action
data class CloseSession(val sessionId: String) : Action
data class SessionsUpdated(val sessions: List<AgentSessionSummary>) : Action
```

当前 `sessionId` 可保留为 `selectedSessionId` 的兼容别名，减少第一阶段改动。

### 7.2 UI

Agent 面板顶部新增一个“新建对话”图标按钮，建议放在 `AgentStatusSummaryCard` 右侧，和折叠按钮同级：

- 使用 Material Icons 的 `Add` 或 `ChatBubbleOutline`。
- 按钮 tooltip/description：`新建对话`。
- 点击后 dispatch `NewSession`。
- 不用大面积说明文案，保持工具型界面密度。

会话列表第一阶段可以做轻量版本：

- 顶部显示当前会话标题或“未命名会话”。
- 新建按钮旁提供下拉菜单，列出最近会话。
- 每项显示标题、状态、更新时间。
- 当前会话高亮。

后续再扩展为左侧/抽屉式会话列表。

### 7.3 ViewModel 行为

启动：

```text
init
  -> startListeningGlobalEvents()
  -> GlobalWebSocketClient.connect()
  -> LoadSessions
  -> 若无会话：NewSession
  -> 若有会话：SelectSession(lastSelectedSessionId)
```

新建：

```text
NewSession
  -> createAgentSession()
  -> 收到 AGENT_SESSION SYNC
  -> selectedSessionId = sessionId
  -> subscribeAgent(sessionId)
  -> messages = emptyList()
```

切换：

```text
SelectSession(newId)
  -> unsubscribeAgent(oldId)
  -> selectedSessionId = newId
  -> subscribeAgent(newId)
  -> 用后端 SYNC 的 latest state 重建当前消息视图
```

销毁：

```text
onCleared
  -> unsubscribeAgent(selectedSessionId)
  -> 不 closeAgentSession
```

关闭会话：

```text
CloseSession(sessionId)
  -> closeAgentSession(sessionId)
  -> 从 sessions 移除
  -> 如果关闭的是当前会话，选择最近一个；没有则 NewSession
```

## 8. 是否需要数据库持久化

第一阶段可不新增数据库表，因为需求核心是“前端停了以后短时间内继续”。当前后端已有 `AgentProcessRegistry` 和内存 `activeSessions`，可以先解决同一服务进程内的会话保留。

但如果要“完全对齐 Claude Code 多 Session 能力”，建议第二阶段新增会话元数据表：

```text
agent_session
  id UUID / varchar session_id
  user_id UUID
  acp_session_id varchar nullable
  title varchar nullable
  work_dir varchar
  status varchar
  last_output_preview varchar
  created_at datetime
  updated_at datetime
  closed_at datetime nullable
```

它只保存会话目录元数据，不保存用户完整 prompt；完整分析结论仍沿用 `agent_analysis_result.content_md`。如果未来需要服务重启后恢复历史对话，再评估是否引入 Claude Code 原生 resume 能力或保存对话 transcript。

数据库文档同步判断：

- 第一阶段只做内存目录和 WS 协议：需要更新 agent/socket/UI skill reference，不需要更新 data-layer 的数据库章节。
- 第二阶段新增 `agent_session` 表：必须更新 `DATA_LAYER_REFERENCE.md`、`agent-architecture.md`、数据库 schema bootstrap 文档。

## 9. 文件级落地计划

### 阶段一：修正会话生命周期，支持多前端 Session

| 文件 | 动作 | 说明 |
|---|---|---|
| `shared/src/commonMain/kotlin/model/ws/AppWebSocket.kt` | 改 | 新增 `AgentSessionSummary`、`AgentSessionSummaryPayload`，可选新增 `AGENT_LIST_SESSIONS` |
| `ktor-server/src/main/kotlin/org/shiroumi/server/websocket/AppWebSocketConnectionManager.kt` | 改 | 处理 `AGENT_LIST_SESSIONS`；`SUBSCRIBE_AGENT` 后触发最新状态 SYNC |
| `ktor-server/src/main/kotlin/org/shiroumi/server/websocket/service/AgentWebSocketService.kt` | 改 | 维护 user 维度会话目录；`onSubscriberJoined` 推最新状态；`closeSession` 仅显式关闭 |
| `compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/service/GlobalWebSocketClient.kt` | 改 | 支持会话列表、selected session、重连后 list + subscribe |
| `compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/ui/agent/state/AgentContract.kt` | 改 | 新增 sessions/selectedSessionId 和 New/Select/Close action |
| `compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/ui/agent/viewmodel/AgentViewModel.kt` | 改 | onCleared 不关闭会话；实现新建/切换/列表同步 |
| `compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/ui/agent/sidebar/AgentSidebarContent.kt` | 改 | 顶部新增新建对话按钮与会话切换入口 |
| `.claude/skills/agent-architect/references/agent-architecture.md` | 改 | 更新 Agent 会话生命周期、WS 链路、文件索引 |
| `.claude/skills/compose-ui-architect/references/ui-architecture.md` | 改 | 更新 Agent UI 状态和会话列表交互 |
| `.claude/skills/socket-protocol/SKILL.md` | 改 | 更新 AGENT topic/command 语义 |

### 阶段二：持久化会话目录

| 文件 | 动作 | 说明 |
|---|---|---|
| `database/src/main/kotlin/org/shiroumi/database/agent/table/AgentSessionTable.kt` | 新增 | 会话元数据表 |
| `database/src/main/kotlin/org/shiroumi/database/agent/repository/AgentSessionRepository.kt` | 新增 | 按 user 查询/创建/关闭/更新时间 |
| `database/src/main/kotlin/org/shiroumi/database/agent/AgentSchemaBootstrap.kt` | 改 | ensure schema |
| `ktor-server/src/main/kotlin/org/shiroumi/server/websocket/service/AgentWebSocketService.kt` | 改 | create/rename/close/update 状态时写元数据 |
| `.claude/skills/data-layer-architect/DATA_LAYER_REFERENCE.md` | 改 | 更新 common_db Agent 表说明 |

### 阶段三：服务重启后的恢复能力

这阶段取决于 Claude Code ACP 是否提供原生 resume/attach 已有 session 的稳定能力：

- 如果 ACP 支持 resume：将 `acpSessionId` 和 workDir 持久化，重启后按原生协议恢复。
- 如果 ACP 不支持 resume：只能恢复会话目录与已持久化报告，不能恢复运行中 prompt。
- 运行中任务跨 JVM 重启恢复不作为第一阶段目标。

## 10. 时序图

### 10.1 新建会话

```text
User
  -> AgentSidebar: 点击新建对话
  -> AgentViewModel: NewSession
  -> GlobalWebSocketClient: AGENT_CREATE_SESSION
  -> AppWebSocketConnectionManager
  -> AgentWebSocketService.createSession(userId)
  -> AgentBridgeImpl.launch()
  -> AcpClient.initialize()
  -> AcpClient.newSession()
  <- AgentWebSocketService: sessionId + acpSessionId
  <- AGENT_SESSION SYNC
  -> AgentViewModel: SessionCreated
  -> GlobalWebSocketClient: SUBSCRIBE_AGENT(sessionId)
  <- AGENT_STREAM SYNC(lastState=IDLE)
```

### 10.2 前端离开后继续

```text
Agent 正在分析
  -> 前端页面销毁
  -> GlobalWebSocketClient: UNSUBSCRIBE_AGENT(sessionId)
  -> AgentWebSocketService.onSubscriberLeft(sessionId)
  -> 后端保留 AgentRuntimeSession 与 Claude ACP Session
  -> Claude 继续运行或停在中断后的同一上下文

用户重新打开前端
  -> WebSocket reconnect
  -> AGENT_LIST_SESSIONS
  <- AGENT_SESSION UPDATE(sessions)
  -> SUBSCRIBE_AGENT(previousSelectedSessionId)
  <- AGENT_STREAM SYNC(lastState)
  -> 用户输入“继续”
  -> AGENT_SEND_PROMPT(same sessionId)
  -> AcpClient.prompt(same acpSessionId, "继续")
```

### 10.3 显式停止后继续

```text
用户点击 Stop
  -> AGENT_STOP(sessionId)
  -> AgentBridgeImpl.interrupt()
  -> AcpClient.interrupt(SIGINT)
  -> StateManager 标记 INTERRUPTED/COMPLETED
  <- AGENT_STREAM UPDATE

用户输入“继续刚才的分析”
  -> AGENT_SEND_PROMPT(same sessionId)
  -> Claude 在同一 ACP Session 内继续对话
```

## 11. 测试计划

### 11.1 单元/集成

- `./gradlew :agent:classes`
- `./gradlew :ktor-server:build`
- `./gradlew :compose-app:build`

### 11.2 手工验收

1. 打开行情页，选中股票，发送买卖点分析。
2. Agent 正在工具调用或输出时关闭 Agent 面板/切换页面，不点击“关闭会话”。
3. 重新打开 Agent 面板，应能看到原会话仍在列表中。
4. 选择原会话，应收到最新 `AGENT_STREAM SYNC`。
5. 输入“继续”，后端应使用同一个业务 `sessionId`，日志里同一个 `acpSessionId`。
6. 点击“新建对话”，应出现空消息列表；输入同样问题时是新上下文。
7. 切回旧会话，旧消息/状态仍在。
8. 点击“关闭会话”，确认后端 `AgentProcessRegistry.unregister(sessionId)`，进程树被清理。
9. WebSocket 断线重连后，`AGENT_LIST_SESSIONS` + `SUBSCRIBE_AGENT` 自动恢复。
10. 用户 A 不能看到用户 B 的 session。

### 11.3 日志验收

关键日志要能串起来：

```text
AGENT_CREATE_SESSION userId=... sessionId=... acpSessionId=...
SUBSCRIBE_AGENT sessionId=...
AGENT_STOP sessionId=... acpSessionId=...
AGENT_SEND_PROMPT sessionId=... acpSessionId=...
AGENT_CLOSE_SESSION sessionId=...
```

## 12. 风险与取舍

| 风险 | 影响 | 对策 |
|---|---|---|
| 前端保留多 session 后，后台 Claude 进程增多 | 内存/进程资源上涨 | 配置最大会话数、空闲 TTL、用户显式关闭 |
| `INTERRUPTED` 新枚举破坏旧前端反序列化 | 旧客户端解析失败 | 第一阶段用 `interrupted: Boolean`，第二阶段再升级枚举 |
| `AGENT_SESSION SYNC` payload 从 String 改对象 | 兼容风险 | 保持旧 String，新增 `UPDATE` 目录事件 |
| Claude Code ACP 不支持进程重启后 resume | 服务重启不能恢复运行中任务 | 第一阶段定义为同一 JVM 生命周期内恢复，跨重启另立阶段 |
| 自动清理策略过短 | 用户切回时 session 已被销毁 | 对 active/detached session 延长 TTL，显式关闭优先 |
| “继续”不是严格恢复被取消的半截响应 | 用户预期偏差 | UI 将 Stop 文案保持为“停止当前回答”，不是“暂停任务” |

## 13. 推荐落地顺序

1. 先改语义：`onCleared` 不再关闭 session，前端只 unsubscribe。
2. 后端 `onSubscriberJoined` 主动推 `lastState`，保证重连/切换立即有画面。
3. 新增前端“新建对话”按钮和单选中的 `selectedSessionId`。
4. 新增 `AGENT_LIST_SESSIONS` 与内存会话目录。
5. 补齐会话切换 UI。
6. 再考虑 `INTERRUPTED` 状态和持久化 `agent_session` 表。

第一阶段完成后，用户遇到的核心 bug 应该消失：只要后端服务和 Claude 进程还在，同一个前端 Agent Session 就能重新订阅并继续对话。
