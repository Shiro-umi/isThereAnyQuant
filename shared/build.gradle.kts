@file:Suppress("unused")
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import java.net.Inet4Address
import java.net.NetworkInterface

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
}

data class DeploymentModeEnvironment(
    val mode: String,
    val testMode: Boolean,
    val host: String,
    val port: Int,
    val publicScheme: String,
    val publicHost: String,
    val publicPort: Int?
) {
    val baseUrl: String
        get() = "$publicScheme://$publicHost"

    val apiBaseUrl: String
        get() = if (publicPort == null) baseUrl else "$baseUrl:$publicPort"

    val wsBaseUrl: String
        get() = apiBaseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
}

/**
 * 自动检测本机首个可用的局域网 IPv4 地址
 * 排除回环、虚拟、down 状态及 link-local 网卡
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
    // 优先选择 RFC1918 私有地址（常见局域网网段），避免优先命中 VPN/代理网卡
    val privateIp = candidates.firstOrNull { ip ->
        ip.startsWith("192.168.") ||
            ip.startsWith("10.") ||
            (ip.startsWith("172.") && ip.split(".")[1].toIntOrNull()?.let { it in 16..31 } == true)
    }
    return privateIp ?: candidates.firstOrNull() ?: "127.0.0.1"
}

fun stripYamlComment(line: String): String {
    var inSingleQuote = false
    var inDoubleQuote = false
    line.forEachIndexed { index, char ->
        when (char) {
            '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
            '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
            '#' -> if (!inSingleQuote && !inDoubleQuote) return line.substring(0, index)
        }
    }
    return line
}

fun readYamlScalar(configFile: File, path: List<String>): String? {
    val stack = mutableListOf<Pair<Int, String>>()
    configFile.readLines().forEach { rawLine ->
        val withoutComment = stripYamlComment(rawLine).trimEnd()
        if (withoutComment.isBlank()) return@forEach
        val indent = withoutComment.indexOfFirst { it != ' ' }.takeIf { it >= 0 } ?: return@forEach
        val trimmed = withoutComment.trim()
        val separatorIndex = trimmed.indexOf(':')
        if (separatorIndex <= 0) return@forEach

        val key = trimmed.substring(0, separatorIndex).trim()
        val value = trimmed.substring(separatorIndex + 1).trim()
        while (stack.isNotEmpty() && stack.last().first >= indent) {
            stack.removeLast()
        }

        val currentPath = stack.map { it.second } + key
        if (currentPath == path) {
            return value
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .takeIf { it.isNotBlank() }
        }
        if (value.isBlank()) {
            stack.add(indent to key)
        }
    }
    return null
}

fun readYamlMapScalars(configFile: File, path: List<String>): Map<String, Map<String, String>> {
    val result = linkedMapOf<String, MutableMap<String, String>>()
    val stack = mutableListOf<Pair<Int, String>>()
    var rootIndent: Int? = null
    var currentEntry: Pair<Int, String>? = null

    configFile.readLines().forEach { rawLine ->
        val withoutComment = stripYamlComment(rawLine).trimEnd()
        if (withoutComment.isBlank()) return@forEach
        val indent = withoutComment.indexOfFirst { it != ' ' }.takeIf { it >= 0 } ?: return@forEach
        val trimmed = withoutComment.trim()
        val separatorIndex = trimmed.indexOf(':')
        if (separatorIndex <= 0) return@forEach

        val key = trimmed.substring(0, separatorIndex).trim()
        val value = trimmed.substring(separatorIndex + 1).trim()
        while (stack.isNotEmpty() && stack.last().first >= indent) {
            stack.removeLast()
        }

        val currentPath = stack.map { it.second } + key
        if (currentPath == path && value.isBlank()) {
            rootIndent = indent
            currentEntry = null
            stack.add(indent to key)
            return@forEach
        }

        val activeRootIndent = rootIndent ?: run {
            if (value.isBlank()) stack.add(indent to key)
            return@forEach
        }
        if (!currentPath.take(path.size).equals(path) || indent <= activeRootIndent) {
            if (value.isBlank()) stack.add(indent to key)
            return@forEach
        }

        if (indent == activeRootIndent + 2 && value.isBlank()) {
            currentEntry = indent to key
            result.getOrPut(key) { linkedMapOf() }
            stack.add(indent to key)
            return@forEach
        }

        val entry = currentEntry
        if (entry != null && indent > entry.first && value.isNotBlank()) {
            result.getOrPut(entry.second) { linkedMapOf() }[key] = value
                .removeSurrounding("\"")
                .removeSurrounding("'")
        }

        if (value.isBlank()) {
            stack.add(indent to key)
        }
    }

    return result
}

fun kotlinStringLiteral(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

fun resolveDeploymentModeEnvironment(configFile: File, mode: String): DeploymentModeEnvironment {
    val prefix = listOf("deployment", "modes", mode)
    val serverPrefix = listOf("server")
    fun scalar(name: String): String? = readYamlScalar(configFile, prefix + name)
    fun serverScalar(name: String): String? = readYamlScalar(configFile, serverPrefix + name)

    val host = scalar("host") ?: serverScalar("host")
        ?: error("Missing deployment.modes.$mode.host or server.host in ${configFile.name}")
    val port = (scalar("port") ?: serverScalar("port"))?.toIntOrNull()
        ?: error("Missing deployment.modes.$mode.port or server.port in ${configFile.name}")
    val testMode = (scalar("testMode") ?: serverScalar("testMode"))?.toBooleanStrictOrNull()
        ?: error("Missing deployment.modes.$mode.testMode or server.testMode in ${configFile.name}")
    val publicScheme = scalar("publicScheme")
        ?: error("Missing deployment.modes.$mode.publicScheme in ${configFile.name}")
    val publicHostRaw = scalar("publicHost") ?: host
    val publicHost = if (publicHostRaw == "auto-lan") detectLanIp() else publicHostRaw
    val publicPortRaw = readYamlScalar(configFile, prefix + "publicPort")
    val publicPort = when {
        publicPortRaw == null -> port
        publicPortRaw.equals("null", ignoreCase = true) -> null
        else -> publicPortRaw.toInt()
    }

    return DeploymentModeEnvironment(
        mode = mode,
        testMode = testMode,
        host = host,
        port = port,
        publicScheme = publicScheme,
        publicHost = publicHost,
        publicPort = publicPort
    )
}

kotlin {
    wasmJs {
        browser()
    }
    jvm()
    // iOS：与 compose-app 的 iOS target 对齐，让前端 iOS app 能消费 shared 的 DTO、协议、
    // 编译期 AppEnvironment 与可微算法工具。仅声明 target，不输出独立 framework——
    // shared 作为 compose-app framework 的传递依赖随其一同被链接。
    // 不含 iosX64：Kotlin 上游废弃 Apple x86_64，与 compose-app 保持同一 target 集合。
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(layout.buildDirectory.dir("generated/sources/kotlin/main"))
            dependencies {
                implementation(libs.kotlin.coroutines.core)
                implementation(libs.kotlin.serialization.json)
                implementation(libs.kotlin.coroutines.core)
                implementation(libs.kotlin.datetime)
//                implementation(libs.runtime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.kaml)
            }
        }
        val wasmJsMain by getting
    }
    jvmToolchain(17)
}

/**
 * 自动化任务：生成前端安全环境配置
 * 数据源：根目录下的 config.yaml (全局唯一真理源)
 */
tasks.register("generateAppEnvironment") {
    group = "build"
    val configFile = rootProject.file("config.yaml").takeIf { it.isFile }
        ?: rootProject.file("config.example.yaml")
    inputs.file(configFile)
    // 本地开发时最常见的入口是 compile / wasmJsBrowserDevelopmentRun / IDE 触发的普通构建，
    // 它们不会带 `packageDebug` 这样的任务名。
    // 旧逻辑在这些情况下会默认落到 release，导致前端继续连接线上地址，
    // 看起来像“本地 server 没收到任何请求”。
    //
    // 这里改成：
    // - 只要任务名明显指向 release 打包，就默认 release
    // - 其他本地开发/编译入口一律默认 debug
    //
    // 显式的 `-Pquant.mode` 或 `QUANT_MODE` 依然拥有更高优先级，
    // 因此不会影响真正的发布流程。
    val defaultQuantMode = if (
        gradle.startParameter.taskNames.any { taskName ->
            taskName.lowercase().contains("release")
        }
    ) {
        "release"
    } else {
        "debug"
    }
    val quantMode = providers.gradleProperty("quant.mode")
        .orElse(providers.environmentVariable("QUANT_MODE"))
        .orElse(defaultQuantMode)
        .map { mode ->
            when (mode.lowercase()) {
                "dev", "debug" -> "debug"
                "debug-wan", "wan" -> "debug-wan"
                "prod", "production", "release" -> "release"
                else -> error("Unsupported quant.mode=$mode, expected debug, debug-wan or release")
            }
        }
    inputs.property("quant.mode", quantMode)

    // 出包链路源头不变量：客户端出包/分发任务（release 打包、Android assemble/bundle、
    // Web 生产分发、iOS embedAndSign、server package 等）绝不允许把内网/LAN/回环地址烧进产物。
    //
    // 触发条件四连乘，缺一不拦，确保只钉死真正的漏点、不误伤本地开发：
    //   ① 本次请求了出包/分发类任务（看 startParameter.taskNames）
    //   ② 既无 -Pquant.mode 也无 QUANT_MODE —— mode 完全靠任务名兜底（无人显式声明）
    //   ③ 解析出的 host 命中 RFC1918 私有段 / loopback（说明兜底落到了 debug 内网分支）
    // 显式声明（无论 -Pquant.mode=debug 还是 =release）一律放行：显式 debug 是开发者主动选择
    // （deploy.sh debug / iOS Debug Run），显式 release 不会落内网。唯一被拦的是「绕过 deploy.sh
    // 手动/IDE 出 release 包却没传 mode → 兜底落 debug → 内网地址」这个真实漏点。
    // 等价于把 iOS verify_ipa 二进制门禁前移到编译期，且零运行时成本、不触碰本地开发链路。
    val packagingTaskRequested = gradle.startParameter.taskNames.any { taskName ->
        val lower = taskName.lowercase()
        lower.contains("release") ||
            lower.contains("assemble") ||
            lower.contains("bundle") ||
            lower.contains("wasmjsbrowserdistribution") ||
            lower.contains("embedandsign") ||
            lower.contains("package")
    }
    val explicitModeDeclared = providers.gradleProperty("quant.mode").orNull != null ||
        providers.environmentVariable("QUANT_MODE").orNull != null

    // 把断言的两个判定纳入增量 key：否则当 quant.mode 与 config.yaml 不变时任务命中 UP-TO-DATE，
    // Gradle 会整体跳过 doLast（含断言），让"先 compile 缓存出 debug 内网产物、再裸跑出包任务"
    // 这一窗口静默旁路断言。纳入后，请求出包任务或显式声明状态一变化即缓存失效、doLast 重跑，
    // 断言始终参与求值。host 内网性由 quant.mode + config.yaml 决定，二者已是 inputs，无需再加。
    inputs.property("quant.packagingTaskRequested", packagingTaskRequested)
    inputs.property("quant.explicitModeDeclared", explicitModeDeclared)

    val generatedDir = layout.buildDirectory.dir("generated/sources/kotlin/main")
    outputs.dir(generatedDir)

    doLast {
        val mode = quantMode.get()
        val environment = resolveDeploymentModeEnvironment(configFile, mode)

        // 出包级不变量断言：出包任务 + 无显式 mode 声明 + host 内网 → 构建期硬失败。
        if (packagingTaskRequested && !explicitModeDeclared) {
            val host = environment.publicHost
            val isIntranetHost = host == "localhost" ||
                host == "127.0.0.1" ||
                host.startsWith("10.") ||
                host.startsWith("192.168.") ||
                Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\.").containsMatchIn(host)
            if (isIntranetHost) {
                error(
                    "出包/分发任务解析出内网 host（mode=$mode, host=$host），且未显式声明 -Pquant.mode/QUANT_MODE。" +
                        "客户端出包必须显式 -Pquant.mode=release（或 QUANT_MODE=release），禁止把内网地址烧进产物。" +
                        "本地开发请使用 wasmJsBrowserDevelopmentRun / iOS Debug / deploy.sh debug；" +
                        "本地 debug 装机（如 assembleDebug）请显式加 -Pquant.mode=debug。"
                )
            }
        }

        val clientDownloadUrl = readYamlScalar(configFile, listOf("client", "downloadUrl")) ?: ""

        val packageName = "org.shiroumi.config"
        val fileName = "AppEnvironment.kt"
        val file = generatedDir.get().asFile.resolve("${packageName.replace('.', '/')}/$fileName")
        file.parentFile.mkdirs()
        
        file.writeText("""
            package $packageName

            /**
             * 编译时生成的安全环境配置
             * 自动同步自根目录 config.yaml；未装配私有配置时回退到 config.example.yaml
             */
            object AppEnvironment {
                const val MODE = "$mode"
                const val IS_TEST_MODE = ${environment.testMode}
                const val BASE_URL = "${environment.baseUrl}"
                const val PORT = ${environment.port}
                const val API_BASE_URL = "${environment.apiBaseUrl}"
                const val WS_BASE_URL = "${environment.wsBaseUrl}"
                const val CLIENT_DOWNLOAD_URL = "${kotlinStringLiteral(clientDownloadUrl)}"

                val apiBaseUrl: String = API_BASE_URL
                val wsBaseUrl: String = WS_BASE_URL
            }
        """.trimIndent())

        val yamlPresets = readYamlMapScalars(configFile, listOf("agent", "modelPresets"))
            .mapNotNull { (key, values) ->
                val displayName = values["displayName"] ?: return@mapNotNull null
                val modelId = values["modelId"] ?: return@mapNotNull null
                val baseUrl = values["baseUrl"]?.takeUnless { it.equals("null", ignoreCase = true) }
                val provider = values["provider"] ?: "anthropic"
                listOf(key, displayName, modelId, baseUrl, provider)
            }
        val presets = yamlPresets
        val defaultModelKey = readYamlScalar(configFile, listOf("agent", "defaultModelKey"))
            ?.takeUnless { it.equals("null", ignoreCase = true) }
            ?: presets.firstOrNull()?.get(0)
        val presetFile = generatedDir.get().asFile
            .resolve("${packageName.replace('.', '/')}/AgentModelPresetCatalog.kt")
        presetFile.parentFile.mkdirs()
        val presetEntries = presets.joinToString(",\n") { preset ->
            val baseUrl = preset[3]?.let { "\"${kotlinStringLiteral(it)}\"" } ?: "null"
            """
                AgentModelPresetDto(
                    key = "${kotlinStringLiteral(preset[0]!!)}",
                    displayName = "${kotlinStringLiteral(preset[1]!!)}",
                    modelId = "${kotlinStringLiteral(preset[2]!!)}",
                    baseUrl = $baseUrl,
                    provider = "${kotlinStringLiteral(preset[4]!!)}"
                )
            """.trimIndent()
        }
        presetFile.writeText("""
            package $packageName

            import model.agent.AgentModelPresetDto

            /**
             * 编译期生成的前端安全 Agent 模型预设目录。
             * 只包含可公开的模型元数据，不包含任何 API Key。
             */
            object AgentModelPresetCatalog {
                val DEFAULT_MODEL_KEY: String? = ${defaultModelKey?.let { "\"${kotlinStringLiteral(it)}\"" } ?: "null"}
                val presets: List<AgentModelPresetDto> = listOf(
                    $presetEntries
                )
            }
        """.trimIndent())
        
        println("[Build] 已生成 AppEnvironment: mode=$mode, apiBaseUrl=${environment.apiBaseUrl}, wsBaseUrl=${environment.wsBaseUrl}")
        println("[Build] 已生成 AgentModelPresetCatalog: presets=${presets.size}, default=$defaultModelKey")
    }
}

// 确保在所有编译任务前先执行生成
tasks.matching { it.name.startsWith("compileKotlin") || it.name.startsWith("compileCommonMain") }.configureEach {
    dependsOn("generateAppEnvironment")
}
