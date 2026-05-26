# Agent 模块

支持通过 ACP (Agent Client Protocol) 与 Claude 交互的 Kotlin 模块。

## 支持的 ACP 后端

本模块支持两种 ACP 后端，按优先级自动选择：

### 1. @zed-industries/claude-agent-acp (推荐)

Zed 官方提供的 ACP 适配器，基于 Claude Agent SDK 构建。

**优点：**
- 更稳定，功能更丰富
- 支持 MCP 服务器
- 支持自定义 Slash 命令
- 更好的错误处理

**安装：**
```bash
# macOS/Linux (Homebrew)
brew install claude-agent-acp

# 或 npm
npm install -g @zed-industries/claude-agent-acp

# 或使用提供的脚本
./install-zed-acp.sh
```

### 2. Claude Code CLI

Anthropic 官方的 Claude Code 命令行工具，使用 `--experimental-acp` 参数启用 ACP 模式。

**安装：**
```bash
npm install -g @anthropic-ai/claude-code
```

## 快速开始

### 1. 安装 ACP 后端

```bash
# 推荐：安装 @zed-industries/claude-agent-acp
./install-zed-acp.sh
```

### 2. 设置 API Key

```bash
export ANTHROPIC_API_KEY=sk-...
```

### 3. 运行交互式测试

```bash
./run-interactive.sh
```

或使用 Gradle：

```bash
./gradlew :agent:agent
```

## 编程使用

### 基本用法

```kotlin
import org.shiroumi.agent.AgentLauncher

// 快速启动（自动检测 ACP 后端）
val result = AgentLauncher.run(
    workDir = "/path/to/project",
    prompt = "请帮我分析这个项目的代码结构"
) { state ->
    println("状态: ${state.status}")
}
```

### 指定 ACP 后端

```kotlin
// 强制使用 @zed-industries/claude-agent-acp
val config = AgentLauncher.Config(
    workDir = "/path/to/project",
    claudeCodePath = "claude-agent-acp",
    preferZedAcpAgent = true
)

val session = AgentLauncher.start(config)
```

### 使用原生 Claude Code

```kotlin
// 强制使用原生 Claude Code
val config = AgentLauncher.Config(
    workDir = "/path/to/project",
    claudeCodePath = "claude",
    preferZedAcpAgent = false
)

val session = AgentLauncher.start(config)
```

### 交互模式

```kotlin
val session = AgentLauncher.start(
    AgentLauncher.Config(
        workDir = projectPath,
        autoApprove = true,
        onStateChange = { state -> updateUI(state) }
    )
)

// 发送 prompt
session.sendPrompt("请帮我重构这段代码")

// 批准/拒绝权限请求
session.approve(requestId)
session.reject(requestId)

// 关闭会话
session.shutdown()
```

## 配置选项

| 选项 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `workDir` | String | `user.dir` | 工作目录 |
| `autoApprove` | Boolean | `false` | 是否自动批准权限请求 |
| `claudeCodePath` | String? | `null` | ACP 后端路径，null 表示自动检测 |
| `preferZedAcpAgent` | Boolean | `true` | 是否优先使用 @zed-industries/claude-agent-acp |

## 项目结构

```
agent/
├── src/main/kotlin/org/shiroumi/agent/
│   ├── AgentLauncher.kt          # 主要入口
│   ├── api/
│   │   └── AgentBridge.kt        # 对外接口
│   ├── bridge/
│   │   └── AcpClient.kt          # ACP SDK 封装
│   ├── impl/
│   │   └── AgentBridgeImpl.kt    # 接口实现
│   └── state/
│       ├── ClaudeState.kt        # 状态定义
│       └── StateManager.kt       # 状态管理
├── build.gradle.kts
├── run-interactive.sh            # 交互式测试脚本
└── install-zed-acp.sh            # 安装 @zed-industries/claude-agent-acp 脚本
```

## 依赖

- `com.agentclientprotocol:acp:0.16.0` - ACP SDK
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` - Kotlin 协程
- `org.jetbrains.kotlinx:kotlinx-serialization-json` - JSON 序列化
- `io.github.oshai:kotlin-logging-jvm` - 日志

## 环境要求

- Java 17+
- Kotlin 1.9+
- Node.js 18+ (如果使用 npm 安装 ACP 后端)

## 参考资料

- [@zed-industries/claude-agent-acp](https://github.com/zed-industries/claude-agent-acp)
- [Agent Client Protocol](https://agentclientprotocol.com/)
- [Zed External Agents](https://zed.dev/docs/ai/external-agents)
