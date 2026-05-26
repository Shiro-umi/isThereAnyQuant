# Agent 子进程生命周期管理

## 背景

`ktor-server` 通过 `AcpClient`（`agent/src/main/kotlin/org/shiroumi/agent/acp/AcpClient.kt`）为每个用户维护一个 Claude Code ACP runtime 进程。前端可以创建多个业务 Agent Session，但这些 session 不再各自拉起 Claude 进程；后端会在同一个进程内通过 ACP `session/new` 创建新的 `ClientSession`，并维护 `business sessionId -> acpSessionId -> runtime` 映射。`claude` 内部仍可能 fork 出 `npm` / MCP server 等孙进程。

当前实现只在 `AcpClient.shutdown()` 调用 `process.destroy()`，发送 SIGTERM 给直接子进程。`claude` 退出时并不保证回收它自己 fork 的 npm / MCP。重启或异常退出 ktor-server 时，这些孙进程脱离父进程被 `launchd` / `init` 收养，形成孤儿，长期堆积。

## 设计原则

- **所有权链清晰**：用进程组（pgid）把整棵子树拴在一起，关停时按组回收，不靠"猜哪些进程是我的"。
- **三层兜底**：应用层钩子 → PID 文件清扫 → OS 层 cgroup/launchd。任意一层失效，下一层补救。
- **会话隔离**：每个 `AcpClient` 实例（=每个用户 Claude Code runtime）独立一个 pgid 和一个 PID 文件；同一用户的多个前端业务 session 共享该 runtime，但绑定不同 ACP session。
- **改动收敛**：核心只动 `AcpClient` 启动/关停两个点 + `ktor-server` 注册表 + 启动清扫。

## 三层方案

### 第 1 层：进程组隔离（必做）

**位置**：`AcpClient.initialize()` 拼 `args` 处（约 132–144 行）。

**做法**：在最终执行命令前包一层 `setsid`，让 claude 成为新 session leader，pgid == claude.pid。其后 claude fork 的 npm/MCP 默认继承 pgid，整棵子树共享一个 pgid。

```kotlin
val rawArgs = buildList {
    add(claudeCmd)
    add("--dangerously-skip-permissions")
    if (config.isolated) {
        add("--setting-sources"); add("project")
        add("--mcp-config"); add("{\"mcpServers\":{}}")
        add("--strict-mcp-config")
    }
}
// 关键：setsid 让 claude 成为新进程组组长
val args = listOf("/usr/bin/env", "setsid") + rawArgs
val processBuilder = ProcessBuilder(args).directory(workDir)
```

> macOS 的 `setsid` 在 `/usr/bin/env setsid` 下需要确认存在；若 BSD `setsid` 缺失，备用方案是写一个 1 行 shell wrapper `exec setsid "$@"`，或用 JNA 调 `setpgid`。

**验证**：`ps -eo pid,pgid,command | grep claude`，应能看到 claude 及其 node/npm 子孙的 pgid 都等于 claude 自己的 pid。

### 第 2 层：注册表 + ApplicationStopping 钩子（必做）

**新文件**：`ktor-server/src/main/kotlin/org/shiroumi/server/agent/AgentProcessRegistry.kt`

职责：

- `register(runtimeId, pid, pgid, workDir)`：进程拉起后调用。Agent runtime 的 key 形如 `agent-runtime-{userId}`。
- `unregister(runtimeId)`：runtime 自然退出或最后一个业务 session 被关闭时调用。
- `shutdownAll(graceMillis = 5000)`：遍历所有条目，先 `kill -TERM -<pgid>`，等 grace，再 `kill -KILL -<pgid>` 兜底。
- 内部用 `ConcurrentHashMap<String, Entry>`。
- 注册成功时同步写 `~/.quant_agents/runtime/pids/<runtimeId>.pid`（内容：`<pgid>\n<startedEpochMs>\n<workDir>`），用于第 3 层。

**接入点**：

1. `AcpClient.initialize()` 拉起进程后：调用回调把 `pid` 透出给 ktor 侧（`AcpClient` 接受 `onProcessStarted: (pid: Long) -> Unit` 构造参数；ktor 这边在回调里 `AgentProcessRegistry.register(runtimeId, pid, pid /*pgid==pid*/, workDir)`）。
2. `AcpClient.shutdown()` **改造**：不再用 `process.destroy()`，改为：
   ```kotlin
   val pid = process?.pid() ?: return
   Runtime.getRuntime().exec(arrayOf("kill", "-TERM", "-$pid"))   // 注意负号 → 整组
   if (!process!!.waitFor(5, TimeUnit.SECONDS)) {
       Runtime.getRuntime().exec(arrayOf("kill", "-KILL", "-$pid"))
   }
   ```
3. ktor `Application.module()` 里：
   ```kotlin
   monitor.subscribe(ApplicationStopping) {
       AgentProcessRegistry.shutdownAll(graceMillis = 5000)
   }
   Runtime.getRuntime().addShutdownHook(Thread {
       AgentProcessRegistry.shutdownAll(graceMillis = 2000)
   })
   ```

> ApplicationStopping 覆盖正常 stop；ShutdownHook 覆盖 `kill -15 <jvm>` 等绕过 ktor 的关停路径。两者都注册不会重复杀进程（registry 内部对每个 entry 有 `terminated` 标记）。

### 第 3 层：启动清扫（必做）

**位置**：`Application.module()` 启动早期，先于任何 WS 接入。

```kotlin
val pidDir = File(System.getProperty("user.home"), ".quant_agents/runtime/pids")
pidDir.listFiles { f -> f.extension == "pid" }?.forEach { f ->
    val (pgidStr) = f.readText().lines()
    val pgid = pgidStr.toLongOrNull() ?: return@forEach
    // 仍然存活且命令名是 claude/node/npm 才杀，避免误杀 pid 重用
    val alive = ProcessHandle.of(pgid).orElse(null)
    if (alive != null && alive.info().command().orElse("").let {
            it.contains("claude") || it.contains("node") || it.contains("npm")
        }) {
        Runtime.getRuntime().exec(arrayOf("kill", "-KILL", "-$pgid"))
    }
    f.delete()
}
```

处理"上一次进程被 `kill -9` / OOM 来不及跑 hook"的场景。

### 第 4 层：OS 兜底（release 推荐）

`./agent.sh` / `./gradlew :ktor-server:run` 的本地开发模式用前三层即可。

**部署**（如果是 Linux + systemd）：

```ini
[Service]
ExecStart=/opt/quant/ktor-server/bin/ktor-server
KillMode=control-group
KillSignal=SIGTERM
TimeoutStopSec=10
```

`KillMode=control-group` 让 systemd 在停服务时 SIGTERM 整个 cgroup，所有子孙一起走，是最干净的兜底。

**macOS launchd** 没有等价 cgroup 机制，但 `ProcessType=Interactive` + `LaunchOnlyOnce=true` 配合前三层已足够。

---

## 改动清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `agent/src/main/kotlin/org/shiroumi/agent/acp/AcpClient.kt` | 改 | `initialize()` 包 `setsid`；`shutdown()` 改成 `kill -TERM -pgid` + 超时 `-KILL`；构造参数加 `onProcessStarted` 回调 |
| `ktor-server/src/main/kotlin/org/shiroumi/server/agent/AgentProcessRegistry.kt` | 新增 | 注册表 + PID 文件持久化 + `shutdownAll` |
| `ktor-server/src/main/kotlin/org/shiroumi/server/websocket/service/AgentWebSocketService.kt` | 改 | 创建 `AcpClient` 时传入 `onProcessStarted` 回调注册到 registry；会话结束时 `unregister` |
| `ktor-server/src/main/kotlin/org/shiroumi/server/Main.kt`（或 `Application.module`） | 改 | 启动清扫；`ApplicationStopping` 订阅；`addShutdownHook` |
| `~/.quant_agents/runtime/pids/`（运行时目录） | 新增 | 不进 git，启动清扫后会自我维护 |

## 验证步骤

1. 启动 ktor-server，连接 2 个 WS 会话，触发 claude 拉起。
2. `pgrep -lf claude` 记录 pid，`ps -o pgid= -p <pid>` 确认 pgid==pid。
3. `ps --ppid <claude_pid>` 看到 node/npm，`ps -o pgid= -p <node_pid>` 确认 pgid 一致。
4. **正常路径**：`./gradlew --stop` 或 ctrl-c → 几秒内 `pgrep -lf claude` 应空。
5. **异常路径**：`kill -9 <jvm_pid>` 模拟崩溃 → 残留进程，重启 ktor-server，启动清扫应清掉。
6. **多会话独立**：开 3 个会话只关闭其中 1 个 WS，确认其他 2 个 claude 进程仍在。

## 风险与权衡

- **`setsid` 平台差异**：macOS `setsid` 行为 OK；若极端情况下不可用，回退用 JNA `setpgid`。
- **PID 重用误杀**：第 3 层清扫已经加 `command name` 校验（必须含 claude/node/npm）；启动后立即清理 PID 文件避免越攒越多。
- **不引入新依赖**：全部用 JDK 自带 `ProcessHandle` + `Runtime.exec("kill", ...)`，符合"未经允许不引入外部依赖"的项目准则。
- **不杀活跃用户会话**：判定依据是 registry 显式注册，不是"看着像孤儿就杀"。

## 落地顺序建议

1. **第 1+2 层一起做**（一次 PR）：进程组 + registry + shutdown 钩子。这是 80% 的收益。
2. **第 3 层**（一次 PR）：启动清扫，处理崩溃残留。
3. **第 4 层**（部署变更，不动代码）：release 时 systemd unit 加 `KillMode=control-group`。
