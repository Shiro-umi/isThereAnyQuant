package org.shiroumi.agent.acp

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val sandboxLogger = KotlinLogging.logger {}

/**
 * OS 层沙箱档位。三态闭合、互斥、自文档,取代「单布尔兼任档位」。
 *
 *  - [OFF]:真无沙箱(LIVE 实盘等非沙箱场景),isEnabled 直接返回 false,零侵入。
 *  - [BACKFILL]:回填档。盘后无人值守的回填 agent 用。网络只放 DNS + 外网 443 + localhost,
 *    禁实时外网按域名直连(只 as-of 历史取数 + LLM)。
 *  - [USER]:放网客。用户交互 agent 用。在回填档基础上把网络放宽为「全放出站」,
 *    支撑 market-emotion/研报/WebSearch 等实时取数。exec/file-write/signal/no-inbound 四道关
 *    与回填档完全相同(拦 start-release.sh、关写到 workDir、杀不了别人、绑不了监听端口)。
 *
 * 档位只决定 network 一段;file-read/file-write/process-exec/signal/no-inbound/process-fork
 * 两档(BACKFILL/USER)逐字相同。
 */
enum class SandboxTier { OFF, BACKFILL, USER }

/**
 * macOS `sandbox-exec` 纯 OS 层沙箱 profile 生成器。
 *
 * 背景与边界(本机实测固化,不要推翻):
 *  - agent 让 claude SDK 在 OS 层 fork 出一整棵子进程树
 *    (node → claude SDK → /bin/sh → bash wrapper → asof 启动器 → java JVM → HTTP 回连本机 server)。
 *    本次事故危害是「agent 起脱缰进程(start-release.sh / java -jar)把生产 server 弄挂」。
 *  - claude SDK 自己 spawn Bash,[org.shiroumi.agent.security.CommandWhitelist] 这条 ACP terminal 通道
 *    从未被 @zed-industries/claude-agent-acp 调用(死代码,保留作语义文档+纵深)。唯一可行关押层只有
 *    macOS `sandbox-exec`,把整棵子进程树罩进 `(deny default)`。沙箱档位见 [SandboxTier]:
 *    回填 agent 走 [SandboxTier.BACKFILL](禁实时外网),用户交互 agent 走 [SandboxTier.USER]
 *    (放实时外网,仍锁 exec/write/signal/no-inbound),[SandboxTier.OFF] 零侵入。
 *
 * 拦「起 server」的真机制(实测坐实):profile 只授 `network-outbound`,从不授 `network-inbound`/
 *  `network-bind` → 沙箱内 `ServerSocket(...)` 直接 `Operation not permitted`。java 能起 JVM
 *  (取数刚需)但绑不了任何监听端口 → 起不了 Ktor/strategy server。`process-exec` deny-default 再拦
 *  projectRoot 根下脚本(start-release.sh / deploy.sh / gradlew → exit 126),`file-write*` 把写关进
 *  workDir + 临时目录,`signal (target self)` 让它杀不了别的进程(kill 生产 PID → Operation not permitted)。
 *
 * 网络真实边界(SBPL 限制,实测):`remote` host 只接受 `*`/`localhost`,无法按 IP/域名收窄外网,
 *  外网维度只能精确到端口。回填档([SandboxTier.BACKFILL])放行 DNS + 外网 443(claude 连 LLM,
 *  生产走 api.deepseek.com)+ localhost 任意端口(取数回连 server),禁实时外网按域名直连。
 *  放网客([SandboxTier.USER])把 network 一段放宽为 `(allow network-outbound)` 全放出站(实时取数刚需),
 *  其余四道关与回填档一字不动。「理论外发数据」不在本次威胁模型内(危害是起进程,不是外发)。
 *
 * 路径全部运行期动态解析为 realpath,禁硬编码版本号。关键实测约束:
 *  - `/var/folders/<user>/T`($TMPDIR,realpath 在 `/private/var/folders/...`)是 JNA/JVM 临时目录,
 *    profile 漏放行会让 asof 启动器报 'temporary directory is not writable' → 取数 100% 挂。必须放行其父。
 *  - exec 白名单按「被执行脚本文件自身路径」判定(非解释器路径)→ workDir/tools 子树必须显式放行,
 *    否则 `./get-candles`、asof 启动器会与攻击脚本一样被 process-exec 拦死。
 *  - `/tmp` 是 `/private/tmp` 的 symlink,sandbox 按 canonical 路径匹配 → 写规则用 realpath + 双写。
 */
object SandboxProfile {

    /** 关闭沙箱的排障开关:`-Dquant.agent.sandbox.disable=true`。 */
    private const val DISABLE_PROP = "quant.agent.sandbox.disable"

    private const val SANDBOX_EXEC = "/usr/bin/sandbox-exec"

    /** 沙箱是否启用:档位非 OFF 且未显式关闭、且本机存在可执行的 sandbox-exec。 */
    fun isEnabled(tier: SandboxTier): Boolean {
        if (tier == SandboxTier.OFF) return false
        // 灰度闸门:健康态恒等返回 requested(此分支不改变行为);TRIPPED/配置地板时返回 OFF。
        // 放在 disable 旗标检查【之前】,因 disable 是更高优先级硬关(运维迁移零风险)。
        if (SandboxRolloutGuard.effectiveTier(tier) == SandboxTier.OFF) {
            sandboxLogger.warn { "[Sandbox] tier=$tier gated OFF by SandboxRolloutGuard" }
            return false
        }
        if (System.getProperty(DISABLE_PROP)?.equals("true", ignoreCase = true) == true) {
            sandboxLogger.warn { "[Sandbox] disabled via -D$DISABLE_PROP=true" }
            return false
        }
        val bin = File(SANDBOX_EXEC)
        if (!bin.canExecute()) {
            sandboxLogger.warn { "[Sandbox] $SANDBOX_EXEC not executable on this host — sandbox OFF" }
            return false
        }
        return true
    }

    /**
     * 生成 `sandbox-exec` 前缀参数:把 profile 落盘到 workDir/.sandbox 下,返回
     * `["/usr/bin/sandbox-exec", "-f", <profilePath>]`,由调用方拼到 claude 命令头部。
     *
     * 写失败(目录不可写/磁盘满/路径解析异常)整体 runCatching 降级为空前缀 → 裸跑不崩,
     * 与「sandbox-exec 缺失」同一容错语义,不把单票打成失败。
     */
    fun execPrefix(workDir: File, tier: SandboxTier, claudeCmd: String): List<String> = runCatching {
        val profileText = render(workDir, tier, claudeCmd)
        val profileFile = File(workDir, ".sandbox/agent.sb").apply {
            parentFile?.mkdirs()
            writeText(profileText)
        }
        sandboxLogger.info { "[Sandbox] profile written: ${profileFile.absolutePath}" }
        listOf(SANDBOX_EXEC, "-f", profileFile.absolutePath)
    }.getOrElse { e ->
        sandboxLogger.warn { "[Sandbox] profile build failed, degrade to bare run: ${e.message}" }
        emptyList()
    }

    /**
     * 渲染 SBPL profile 文本。所有路径运行期解析为 realpath,规避 symlink 匹配陷阱。
     *
     * @param claudeCmd ACP 启动命令(`claude-agent-acp` / 绝对路径 / `claude`)。其真身目录(软链全展开,
     *   如 `node_modules/@zed-industries/claude-agent-acp/dist`)+ node_modules 根必须进 exec 白名单——
     *   否则 node 起 ACP js 时按真身路径判 `process-exec` 被拦,`execvp() Operation not permitted`,agent 起不来。
     *
     * 档位只影响 network 一段:[SandboxTier.BACKFILL] 走端口白名单(DNS+443+localhost,禁实时外网),
     * [SandboxTier.USER] 走全放出站(覆盖 DNS/外网任意端口/localhost)。其余所有段两档逐字相同。
     * [SandboxTier.OFF] 不应到达此处(isEnabled 已拦),防御性按回填档渲染。
     */
    fun render(workDir: File, tier: SandboxTier, claudeCmd: String): String {
        val workDirReal = workDir.canonicalRealPath()
        val projectRoot = resolveProjectRoot().canonicalRealPath()
        val toolsDir = File(projectRoot, "tools").canonicalRealPath()
        val javaHome = resolveJavaHome()?.canonicalRealPath()
        val nodeDir = resolveNodeDir()         // node 真身所在目录,运行期 which 解析,不写死 /opt/homebrew
        val darwinUserDir = resolveDarwinUserDir() // $TMPDIR realpath 的父(含 T/C),JNA/JVM 临时目录刚需
        val acpDirs = resolveAcpExecDirs(claudeCmd) // claude-agent-acp 真身 dist 目录 + node_modules 根

        // exec 白名单子树(去重)。/bin /usr/bin 覆盖 sh/bash/env/uname/ls/sed/tr/xargs/bc。
        val execSubtrees = buildList {
            add("/bin")
            add("/usr/bin")
            add("/usr/libexec")                // /usr/libexec/java_home(JAVA_HOME 未设时 stub 回调)
            add(toolsDir)
            add(workDirReal)
            javaHome?.let { add("$it/bin") }    // asof 启动器走 $JAVA_HOME/bin/java
            javaHome?.let { add(it) }
            nodeDir?.let { add(it) }
            addAll(acpDirs)                     // claude-agent-acp 真身目录(软链展开)+ node_modules 根
        }.distinct()

        // file-write 收窄:仅 workDir + 临时目录(/private/tmp + /tmp 双保险 + $TMPDIR 父)+ 必要字符设备。
        val writeSubtrees = buildList {
            add("/private/tmp")
            add("/tmp")
            add(workDirReal)
            darwinUserDir?.let { add(it) }
        }.distinct()

        return buildString {
            appendLine("(version 1)")
            appendLine("(deny default)")
            appendLine()
            appendLine(";; ---- 进程与信号(JVM/子进程树刚需);只授 fork+对自身发信号,不授 network-inbound ----")
            appendLine("(allow process-fork)")
            appendLine("(allow signal (target self))")
            appendLine("(allow sysctl-read)")
            appendLine("(allow mach-lookup)")
            appendLine("(allow iokit-open)")
            appendLine()
            appendLine(";; ---- 文件读:全放(系统库/字体/tzdata/jar/catalog,取数链刚需) ----")
            appendLine("(allow file-read*)")
            appendLine()
            appendLine(";; ---- 文件写:仅 workDir + 临时目录(TMPDIR/JNA)+ 字符设备 ----")
            appendLine("(allow file-write*")
            writeSubtrees.forEach { appendLine("    (subpath ${q(it)})") }
            appendLine("    (literal \"/dev/null\")")
            appendLine("    (literal \"/dev/zero\")")
            appendLine("    (literal \"/dev/urandom\")")
            appendLine("    (literal \"/dev/random\")")
            appendLine("    (literal \"/dev/dtracehelper\")")
            appendLine("    (literal \"/dev/tty\"))")
            appendLine()
            appendLine(";; ---- 可执行白名单:取数链整棵树(按脚本文件自身路径判定) ----")
            appendLine("(allow process-exec")
            execSubtrees.forEach { appendLine("    (subpath ${q(it)})") }
            appendLine("    (literal \"/usr/bin/java\"))")
            appendLine()
            when (tier) {
                SandboxTier.USER -> {
                    // 放网客:全放出站(不限端口/域名,覆盖 DNS/外网/localhost/unix-socket),
                    // 支撑 market-emotion/研报/WebSearch 等实时取数。仍从不授 inbound/bind → 起不了 server。
                    appendLine(";; ---- 网络:全放出站(USER 档,实时取数);无 inbound/bind ----")
                    appendLine("(allow network-outbound)")
                }
                else -> {
                    // 回填档(BACKFILL,及 OFF 防御渲染):DNS + 外网 443(LLM) + localhost 任意端口(取数回连);
                    // 禁实时外网按域名直连;无 inbound/bind。
                    appendLine(";; ---- 网络:DNS + 外网 443(LLM) + localhost 任意端口(取数回连);无 inbound/bind ----")
                    appendLine("(allow network-outbound (remote udp \"*:53\"))")
                    appendLine("(allow network-outbound (remote tcp \"*:53\"))")
                    appendLine("(allow network-outbound (remote unix-socket))")
                    appendLine("(allow network-outbound (remote tcp \"*:443\"))")
                    appendLine("(allow network-outbound (remote ip \"localhost:*\"))")
                }
            }
        }
    }

    /**
     * projectRoot 必须与 [org.shiroumi.agententry.AgentEntryBackfiller.resolveProjectRoot] 同源:
     * `quant.project.root` → `quant.projectRoot` → `user.dir`。不从 workDir 上溯
     * (workDir 在 `~/.quant_entry_backfill/...` 即 projectRoot 之外)。
     */
    private fun resolveProjectRoot(): File = File(
        System.getProperty("quant.project.root")
            ?: System.getProperty("quant.projectRoot")
            ?: System.getProperty("user.dir")
    )

    /**
     * JAVA_HOME 子树解析:优先 `JAVA_HOME` env(取数启动器首选分支),回退 `/usr/libexec/java_home`。
     * 解析失败返回 null(此时靠 `/usr/bin/java` literal + `/usr/libexec` 兜底)。
     */
    private fun resolveJavaHome(): File? {
        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let {
            val f = File(it); if (f.exists()) return f
        }
        return runCatching {
            val p = ProcessBuilder("/usr/libexec/java_home").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
        }.getOrElse {
            sandboxLogger.warn { "[Sandbox] /usr/libexec/java_home failed: ${it.message}" }
            null
        }
    }

    /**
     * node 真身所在目录:运行期 `/usr/bin/which node` 解析,展开 realpath 后取父目录。
     * homebrew/nvm/asdf/系统 node 均覆盖,不写死 `/opt/homebrew`。解析失败回退 `/opt/homebrew`。
     */
    private fun resolveNodeDir(): String? = runCatching {
        val p = ProcessBuilder("/usr/bin/which", "node").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.takeIf { it.isNotBlank() }
            ?.let { File(it).canonicalFile.parentFile?.absolutePath }
    }.getOrElse { null } ?: "/opt/homebrew".takeIf { File(it).exists() }

    /**
     * claude-agent-acp 真身相关的 exec 白名单目录:
     *  1. ACP 启动器真身所在目录(软链全展开,如 `.../@zed-industries/claude-agent-acp/dist`)——
     *     node 起这个 js 时按真身路径判 `process-exec`,不放行即 `execvp() Operation not permitted`,agent 起不来。
     *  2. node_modules 根(`.../node_modules`)——ACP 运行期可能 spawn 同根下其他工具(如内置 ripgrep)。
     *
     * claudeCmd 为绝对路径直接展开;为命令名(`claude-agent-acp`)走 `/usr/bin/which` 解析。
     * 任一步失败只跳过该项(已放行的 nodeDir/系统目录仍在),不抛断渲染。
     */
    private fun resolveAcpExecDirs(claudeCmd: String): List<String> = runCatching {
        val launcher = File(claudeCmd).takeIf { it.isAbsolute && it.exists() }
            ?: run {
                val p = ProcessBuilder("/usr/bin/which", claudeCmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                out.takeIf { it.isNotBlank() }?.let(::File)
            }
            ?: return@runCatching emptyList()
        val real = launcher.toPath().toRealPath().toString()       // 软链全展开到真身 js
        buildList {
            File(real).parentFile?.absolutePath?.let { add(it) }   // dist 目录
            // node_modules 根:真身路径里截到 `/node_modules`(含),覆盖整个依赖树。
            val idx = real.indexOf("/node_modules")
            if (idx >= 0) add(real.substring(0, idx + "/node_modules".length))
        }.distinct()
    }.getOrElse {
        sandboxLogger.warn { "[Sandbox] resolve ACP exec dirs failed for '$claudeCmd': ${it.message}" }
        emptyList()
    }

    /**
     * macOS per-user 临时/缓存目录的父(`/private/var/folders/<hash>/`,含 `T`=$TMPDIR 与 `C`=cache)。
     * JNA 解压 native dispatch、JVM tmp 文件都写这里;漏放行 → asof 启动器 'not writable' 取数挂。
     * 解析自 `System.getenv("TMPDIR")` 的 realpath 父目录。失败返回 null(退化为只放 /tmp,取数可能挂,有日志)。
     */
    private fun resolveDarwinUserDir(): String? = runCatching {
        System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }
            ?.let { File(it).toPath().toRealPath().parent?.toString() }
    }.getOrElse {
        sandboxLogger.warn { "[Sandbox] resolve TMPDIR parent failed: ${it.message}" }
        null
    }

    /** 解析为 canonical realpath(symlink 全展开),失败回退绝对路径。 */
    private fun File.canonicalRealPath(): String =
        runCatching { toPath().toRealPath().toString() }.getOrElse { absolutePath }

    /** SBPL 字符串转义:profile 用 `"..."` 包裹路径,转义反斜杠与双引号。 */
    private fun q(path: String): String =
        "\"${path.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
