package org.shiroumi.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.inputStream

/**
 * 跨模块配置管理器 (JVM)
 */
object ConfigManager {
    
    private val possibleConfigPaths: List<Path>
        get() {
            val explicitPath = System.getProperty("config.file")?.let { Paths.get(it) }
            return listOfNotNull(
                explicitPath,
                Paths.get("config.yaml"),
                Paths.get("../config.yaml"),
                Paths.get("../../config.yaml")
            )
        }
    
    private var loadedConfigPath: Path? = null
    
    fun load(): QuantConfig {
        val configPath = possibleConfigPaths.firstOrNull { it.exists() }
        
        val config = if (configPath != null && configPath.exists()) {
            try {
                loadedConfigPath = configPath
                val yamlContent = configPath.toFile().readText()
                println("[ConfigManager] 配置文件内容预览:\n${yamlContent.take(500)}")
                configPath.inputStream().use { input ->
                    Yaml.default.decodeFromStream<QuantConfig>(input)
                }
            } catch (e: Exception) {
                println("[ConfigManager] 配置文件解析失败: $configPath, ${e.message}")
                e.printStackTrace()
                QuantConfig()
            }
        } else {
            println("[ConfigManager] 未找到配置文件，使用默认配置")
            QuantConfig()
        }

        val resolvedConfig = resolveEnvOverrides(config)
        println("[ConfigManager] 配置加载完成，externalApis.tushareToken='${resolvedConfig.externalApis.tushareToken}'")
        ConfigHolder.update(resolvedConfig)
        return resolvedConfig
    }
    
    fun getConfig(): QuantConfig = ConfigHolder.config

    fun getLoadedConfigPath(): String = loadedConfigPath?.toString() ?: "(default)"

    /**
     * 将配置中的空白敏感字段替换为对应的环境变量值
     */
    private fun resolveEnvOverrides(config: QuantConfig): QuantConfig {
        val anthropicApiKey = System.getenv("ANTHROPIC_API_KEY")
        val agentConfig = if (config.agent.apiKey.isBlank() && !anthropicApiKey.isNullOrBlank()) {
            println("[ConfigManager] agent.apiKey resolved from ANTHROPIC_API_KEY env var")
            config.agent.copy(apiKey = anthropicApiKey)
        } else {
            config.agent
        }

        // 支持从环境变量覆盖部署模式；QUANT_MODE 优先级高于 QUANT_TEST_MODE。
        // QUANT_MODE 只选择 config.yaml 中的 deployment.modes，不在中间层写死 host/port。
        val quantModeEnv = System.getenv("QUANT_MODE")?.lowercase()
        val testModeEnv = System.getenv("QUANT_TEST_MODE")
        val serverConfig = when (quantModeEnv) {
            "dev", "debug" -> resolveDeploymentServerConfig(config, "debug", "QUANT_MODE=$quantModeEnv")
            "debug-wan", "wan" -> resolveDeploymentServerConfig(config, "debug-wan", "QUANT_MODE=$quantModeEnv")
            "prod", "production", "release" -> resolveDeploymentServerConfig(config, "release", "QUANT_MODE=$quantModeEnv")
            null -> if (testModeEnv != null) {
                val testMode = testModeEnv.toBoolean()
                val matchingMode = config.deployment.modes.entries.firstOrNull { entry ->
                    entry.value.testMode == testMode
                }?.key
                if (matchingMode != null) {
                    resolveDeploymentServerConfig(config, matchingMode, "QUANT_TEST_MODE=$testModeEnv")
                } else {
                    println("[ConfigManager] server.testMode resolved from QUANT_TEST_MODE env var: $testMode")
                    config.server.copy(testMode = testMode)
                }
            } else {
                val defaultMode = normalizeDeploymentMode(config.deployment.defaultMode)
                resolveDeploymentServerConfig(config, defaultMode, "deployment.defaultMode")
            }
            else -> {
                println("[ConfigManager] 忽略未知 QUANT_MODE=$quantModeEnv，使用配置文件中的 server 配置")
                config.server
            }
        }

        return config.copy(agent = agentConfig, server = serverConfig)
    }

    /**
     * 自动检测本机首个可用的局域网 IPv4 地址
     * 排除回环、虚拟、down 状态及 link-local 网卡
     * 优先选择 RFC1918 私有地址，避免优先命中 VPN/代理网卡
     */
    fun detectLanIp(): String {
        val candidates = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
            val addresses = ni.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    candidates.add(addr.hostAddress)
                }
            }
        }
        val privateIp = candidates.firstOrNull { ip ->
            ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                (ip.startsWith("172.") && ip.split(".")[1].toIntOrNull()?.let { it in 16..31 } == true)
        }
        return privateIp ?: candidates.firstOrNull() ?: "127.0.0.1"
    }

    private fun normalizeDeploymentMode(mode: String): String = when (mode.lowercase()) {
        "dev", "debug" -> "debug"
        "debug-wan", "wan" -> "debug-wan"
        "prod", "production", "release" -> "release"
        else -> error("Unsupported deployment mode=$mode, expected debug, debug-wan or release")
    }

    private fun resolveDeploymentServerConfig(
        config: QuantConfig,
        mode: String,
        source: String
    ): ServerConfig {
        val normalizedMode = normalizeDeploymentMode(mode)
        val modeConfig = config.deployment.modes[normalizedMode]
            ?: error("Missing deployment.modes.$normalizedMode in config.yaml")

        val resolvedPublicHost = when (modeConfig.publicHost) {
            "auto-lan" -> detectLanIp()
            null -> modeConfig.host ?: config.server.host
            else -> modeConfig.publicHost
        }
        val resolvedPublicScheme = modeConfig.publicScheme ?: "http"
        val resolvedPublicPort = modeConfig.publicPort ?: modeConfig.port ?: config.server.port

        val resolved = config.server.copy(
            testMode = modeConfig.testMode ?: config.server.testMode,
            port = modeConfig.port ?: config.server.port,
            host = modeConfig.host ?: config.server.host,
            publicHost = resolvedPublicHost,
            publicScheme = resolvedPublicScheme,
            publicPort = resolvedPublicPort
        )
        println(
            "[ConfigManager] server resolved from $source: " +
                "mode=$normalizedMode, testMode=${resolved.testMode}, port=${resolved.port}, host=${resolved.host}, " +
                "publicHost=${resolved.publicHost}, publicScheme=${resolved.publicScheme}, publicPort=${resolved.publicPort}"
        )
        return resolved
    }
}
