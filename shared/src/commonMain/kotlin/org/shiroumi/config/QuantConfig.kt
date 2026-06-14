package org.shiroumi.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Quant Server 统一配置类
 */
@Serializable
data class QuantConfig(
    val server: ServerConfig = ServerConfig(),
    val deployment: DeploymentConfig = DeploymentConfig(),
    val llm: LlmConfig = LlmConfig(),
    val scheduler: SchedulerConfig = SchedulerConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val logging: LoggingConfig = LoggingConfig(),
    val skills: SkillsConfig = SkillsConfig(),
    val features: FeaturesConfig = FeaturesConfig(),
    val externalApis: ExternalApisConfig = ExternalApisConfig(),
    val strategy: StrategyConfig = StrategyConfig(),
    val auth: AuthConfig = AuthConfig(),
    val agent: AgentConfig = AgentConfig()
)

@Serializable
@SerialName("server")
data class ServerConfig(
    val port: Int = 9870,
    val testMode: Boolean = false,
    val host: String = "localhost",
    val dataUpdateWsPath: String = "/ws/data-update",
    val publicHost: String = host,
    val publicScheme: String = "http",
    val publicPort: Int = port
)

@Serializable
@SerialName("deployment")
data class DeploymentConfig(
    val defaultMode: String = "debug",
    val modes: Map<String, DeploymentModeConfig> = emptyMap()
)

@Serializable
@SerialName("deploymentMode")
data class DeploymentModeConfig(
    val testMode: Boolean? = null,
    val host: String? = null,
    val port: Int? = null,
    val publicScheme: String? = null,
    val publicHost: String? = null,
    val publicPort: Int? = null
)

@Serializable
data class ExternalApisConfig(
    val tushareToken: String = ""
)

@Serializable
@SerialName("strategy")
data class StrategyConfig(
    val scriptBaseDir: String = ".claude/scripts",
    val scriptBaseClientDir: String = "py_backtest",
    val strategyServiceHost: String = "127.0.0.1",
    val strategyServiceBindHost: String = "127.0.0.1",
    val strategyServicePort: Int = 9971
)

@Serializable
@SerialName("llm")
data class LlmConfig(
    val defaultModel: String = "deepseek-chat",
    val models: Map<String, ModelConfig> = emptyMap()
) {
    fun getDefaultModelConfig(): ModelConfig? = models[defaultModel]
    fun getModelConfig(name: String?): ModelConfig? = models[name ?: defaultModel] ?: getDefaultModelConfig()
}

@Serializable
@SerialName("model")
data class ModelConfig(
    val provider: String = "DEEPSEEK",
    val modelId: String = "deepseek-chat",
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com/v1",
    val thinkingLevel: String = "MEDIUM",
    val timeoutMs: Long = 120000,
    val maxTokens: Int = 8192,
    val temperature: Double = 0.7
)

@Serializable
@SerialName("scheduler")
data class SchedulerConfig(
    val pendingPollIntervalMs: Long = 1000,
    val taskListBroadcastIntervalMs: Long = 1000,
    val statusCheckIntervalMs: Long = 5000
)

@Serializable
@SerialName("database")
data class DatabaseConfig(
    val host: String = "127.0.0.1",
    val port: Int = 3306,
    val user: String = "remote",
    val password: String = "",
    val pool: DatabasePoolConfig = DatabasePoolConfig()
)

@Serializable
@SerialName("databasePool")
data class DatabasePoolConfig(
    /**
     * 单库连接池峰值连接数。每个分库（common_db / stock_db）各自维护一个 HikariCP 池，
     * 总连接数 = 分库数 × maxConnections。32G 内存机器按中等并发推荐 25。
     * 提升此值时必须同步抬高 MySQL `max_connections`（默认 151），否则 MySQL 端拒绝新连接。
     */
    val maxConnections: Int = 25,
    /**
     * 常驻空闲连接数。HikariCP 保持至少这么多连接随时可用，避开冷连接握手延迟。
     * 取值必须 ≤ maxConnections；越界时由装配层 coerce 截断到 maxConnections。
     */
    val minimumIdle: Int = 8,
    val connectionTimeoutMs: Long = 5000,
    val socketTimeoutMs: Int = 60000
)

@Serializable
@SerialName("logging")
data class LoggingConfig(
    val level: String = "INFO",
    val file: LogFileConfig = LogFileConfig(),
    val console: LogConsoleConfig = LogConsoleConfig()
)

@Serializable
@SerialName("logFile")
data class LogFileConfig(
    val enabled: Boolean = true,
    val path: String = "logs/quant-server.log",
    val maxSize: String = "100MB",
    val maxHistory: Int = 30
)

@Serializable
@SerialName("logConsole")
data class LogConsoleConfig(
    val enabled: Boolean = true,
    val color: Boolean = true
)

@Serializable
@SerialName("skills")
data class SkillsConfig(
    val basePath: String = ".claude/skills",
    val cacheEnabled: Boolean = true,
    val cacheExpireMs: Long = -1
)

@Serializable
@SerialName("features")
data class FeaturesConfig(
    val autoDataUpdate: Boolean = true,
    val websocketStreaming: Boolean = true,
    val taskPersistence: Boolean = true,
    val skillSystem: Boolean = true
)

@Serializable
@SerialName("agent")
data class AgentConfig(
    /** Claude Code 可执行文件路径，null 时自动检测 */
    val claudeCommand: String? = null,
    /** Anthropic API Key，注入到 claude 进程的 ANTHROPIC_API_KEY 环境变量 */
    val apiKey: String = "",
    /** Agent 工作目录，null 时使用服务进程工作目录 */
    val workDir: String? = null,
    /** 是否隔离全局 skill/MCP，只加载 workDir/.claude/ 下的配置 */
    val isolated: Boolean = true,
    /** 隔离环境的配置目录路径，开发环境默认为 workDir/.claude-isolated */
    val configDir: String? = null,
    /** 自定义模型 ID (如 stepfun/step-3.5-flash:free) */
    val modelId: String? = null,
    /** 自定义 API Base URL (如 https://openrouter.ai/api/v1) */
    val baseUrl: String? = null,
    /** 前端可选择的默认预设模型 key */
    val defaultModelKey: String? = null,
    /** 服务端可用的 Agent 模型预设；API Key 仅供后端启动 Claude Code 进程使用，不下发前端 */
    val modelPresets: Map<String, AgentModelPresetConfig> = emptyMap()
)

@Serializable
@SerialName("agentModelPreset")
data class AgentModelPresetConfig(
    val displayName: String,
    val modelId: String,
    val baseUrl: String? = null,
    val provider: String = "anthropic",
    val apiKey: String? = null
)

@Serializable
@SerialName("auth")
data class AuthConfig(
    val jwt: JwtConfig = JwtConfig(),
    val password: PasswordConfig = PasswordConfig(),
    val cors: CorsConfig = CorsConfig()
)

@Serializable
@SerialName("jwt")
data class JwtConfig(
    val secret: String = "your-secret-key-change-in-production",
    val issuer: String = "quant-server",
    val audience: String = "quant-client",
    val realm: String = "Quant Trading System"
)

@Serializable
@SerialName("password")
data class PasswordConfig(
    val minLength: Int = 8,
    val requireUppercase: Boolean = true,
    val requireLowercase: Boolean = true,
    val requireDigit: Boolean = true,
    val bcryptRounds: Int = 12
)

@Serializable
@SerialName("cors")
data class CorsConfig(
    val allowedOrigins: List<String> = listOf(
        "http://localhost:9871",
        "http://127.0.0.1:9871"
    ),
    val allowedMethods: List<String> = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS"),
    val allowedHeaders: List<String> = listOf("Authorization", "Content-Type", "Accept"),
    val allowCredentials: Boolean = true,
    val maxAge: Int = 3600
)
