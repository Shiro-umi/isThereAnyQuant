package org.shiroumi.agent.acp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * [SandboxProfile] 集成单测:用真实 `render()` 产出的 profile + 真 `sandbox-exec` 验证隔离边界。
 *
 * 把「代码产出的 profile 真的拦住事故攻击、真的不误伤取数链」锁进 CI。非 macOS / 无 sandbox-exec 的
 * 环境(assumeTrue)优雅跳过,不让 CI 红。所有进程都在临时 workDir + 临时 projectRoot 下跑,
 * 不触碰真实项目根、不起任何服务。
 *
 * 事故复现锚点:2026-06-20 回填 agent 在应用层伪隔离下执行 start-release.sh 把生产 ktor 弄挂。
 * 本测试的「攻击脚本被拦」用例即该事故的最小复现。
 */
class SandboxProfileTest {

    private val sandboxExec = File("/usr/bin/sandbox-exec")

    /** ACP 启动命令名;render/execPrefix 据此把其真身目录放进 exec 白名单。本机存在即可解析。 */
    private val acpCmd = "claude-agent-acp"

    private fun sandboxAvailable(): Boolean =
        System.getProperty("os.name").contains("Mac", ignoreCase = true) && sandboxExec.canExecute()

    /** 在 profile 沙箱内跑 `sh -c <script>`,返回 (exitCode, mergedOutput)。 */
    private fun runInSandbox(profile: File, script: String): Pair<Int, String> {
        val p = ProcessBuilder(
            "/usr/bin/sandbox-exec", "-f", profile.absolutePath, "/bin/sh", "-c", script
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    /**
     * 造一个临时 projectRoot(含 tools/ 与一个攻击脚本),用 -Dquant.project.root 让 render 解析到它,
     * 在临时 workDir 下生成真 profile。返回 (workDir, projectRoot, profileFile)。
     *
     * projectRoot 与 workDir 都建在 $HOME 下(不能用 /tmp/$TMPDIR——那是 profile 的写白名单,
     * 会让「写项目根被拦」用例假阴)。$HOME 子目录默认不在任何写/exec 白名单,贴合真实部署。
     */
    private fun setup(tier: SandboxTier = SandboxTier.BACKFILL): Triple<File, File, File> {
        val home = File(System.getProperty("user.home"))
        val nonce = System.nanoTime().toString()
        val projectRoot = File(home, "sbx_repo_$nonce").apply { mkdirs() }
        File(projectRoot, "tools").mkdirs()
        // 攻击脚本放项目根(模拟 start-release.sh / deploy.sh):不在 exec 白名单子树,应被 process-exec 拦。
        File(projectRoot, "start-release.sh").apply {
            writeText("#!/bin/bash\necho STARTED_SERVER\n")
            setExecutable(true)
        }
        val workDir = File(home, "sbx_wd_$nonce").apply { mkdirs() }

        val prev = System.getProperty("quant.project.root")
        System.setProperty("quant.project.root", projectRoot.absolutePath)
        try {
            val prefix = SandboxProfile.execPrefix(workDir, tier, acpCmd)
            assertEquals(3, prefix.size, "execPrefix 应返回 [sandbox-exec, -f, path]")
            return Triple(workDir, projectRoot, File(prefix[2]))
        } finally {
            if (prev != null) System.setProperty("quant.project.root", prev)
            else System.clearProperty("quant.project.root")
        }
    }

    private fun cleanup(vararg dirs: File) = dirs.forEach { it.deleteRecursively() }

    @Test
    fun `事故复现 项目根攻击脚本被 process-exec 拦死`() {
        assumeTrue(sandboxAvailable(), "非 macOS 或无 sandbox-exec,跳过")
        val (workDir, projectRoot, profile) = setup()
        try {
            val (code, out) = runInSandbox(profile, "${projectRoot.absolutePath}/start-release.sh")
            assertFalse(out.contains("STARTED_SERVER"), "攻击脚本不该执行成功: $out")
            assertTrue(
                out.contains("Operation not permitted") || code != 0,
                "start-release.sh 应被沙箱拦住(exit=$code, out=$out)"
            )
        } finally {
            cleanup(workDir, projectRoot)
        }
    }

    @Test
    fun `JVM 起得来但 bind 监听端口被拦`() {
        assumeTrue(sandboxAvailable(), "非 macOS 或无 sandbox-exec,跳过")
        val javaHome = System.getenv("JAVA_HOME")
            ?: System.getProperty("java.home")
        assumeTrue(javaHome != null && File(javaHome, "bin/java").canExecute(), "无可用 java,跳过")
        val (workDir, projectRoot, profile) = setup()
        try {
            val src = File(workDir, "Bind.java").apply {
                writeText(
                    """
                    public class Bind { public static void main(String[] a) throws Exception {
                      try { java.net.ServerSocket s=new java.net.ServerSocket(0); System.out.println("BIND_OK"); s.close(); }
                      catch (Exception e){ System.out.println("BIND_FAILED:"+e.getMessage()); } } }
                    """.trimIndent()
                )
            }
            val javac = File(javaHome, "bin/javac").absolutePath
            val java = File(javaHome, "bin/java").absolutePath
            // 编译在沙箱内(写 .class 到 workDir,放行);javac 自身在 JAVA_HOME/bin 白名单内。
            val (cc, _) = runInSandbox(profile, "cd ${workDir.absolutePath} && $javac Bind.java")
            assertEquals(0, cc, "javac 应在沙箱内成功(JAVA_HOME 在 exec 白名单)")
            val (_, out) = runInSandbox(profile, "$java -cp ${workDir.absolutePath} Bind")
            assertTrue(out.contains("BIND_FAILED"), "ServerSocket 应被拦(起不了 server): $out")
            assertFalse(out.contains("BIND_OK"), "不该绑上端口: $out")
        } finally {
            cleanup(workDir, projectRoot)
        }
    }

    @Test
    fun `写 workDir 通 写项目根被拦`() {
        assumeTrue(sandboxAvailable(), "非 macOS 或无 sandbox-exec,跳过")
        val (workDir, projectRoot, profile) = setup()
        try {
            val (okCode, _) = runInSandbox(profile, "echo ok > ${workDir.absolutePath}/w.txt")
            assertEquals(0, okCode, "写 workDir 应放行")
            assertTrue(File(workDir, "w.txt").exists())

            val hack = File(projectRoot, "hack.txt")
            runInSandbox(profile, "echo x > ${hack.absolutePath}")
            assertFalse(hack.exists(), "写项目根应被沙箱拦住")
        } finally {
            cleanup(workDir, projectRoot)
        }
    }

    @Test
    fun `杀其他进程被拦 只能对自身发信号`() {
        assumeTrue(sandboxAvailable(), "非 macOS 或无 sandbox-exec,跳过")
        val (workDir, projectRoot, profile) = setup()
        val victim = ProcessBuilder("/bin/sleep", "30").start()
        try {
            val (code, out) = runInSandbox(profile, "/bin/kill -TERM ${victim.pid()}")
            assertTrue(victim.isAlive, "受害进程应存活(沙箱只授 signal target self)")
            assertTrue(code != 0 || out.contains("Operation not permitted"), "kill 应被拦: $out")
        } finally {
            victim.destroyForcibly()
            cleanup(workDir, projectRoot)
        }
    }

    @Test
    fun `bc 计算放行`() {
        assumeTrue(sandboxAvailable(), "非 macOS 或无 sandbox-exec,跳过")
        val (workDir, projectRoot, profile) = setup()
        try {
            val (code, out) = runInSandbox(profile, "echo 'scale=2; 7/2' | bc")
            assertEquals(0, code, "bc 应放行")
            assertTrue(out.trim() == "3.50", "bc 结果应为 3.50,实际=$out")
        } finally {
            cleanup(workDir, projectRoot)
        }
    }

    @Test
    fun `回归 exec 白名单放行 claude-agent-acp 真身目录`() {
        // 锁住 2026-06-20 部署后用户连不上 agent 的 bug:claude-agent-acp 是软链,真身 js 在
        // node_modules/@zed-industries/.../dist,漏放行→node 起 ACP js 时 execvp() Operation not permitted。
        val which = ProcessBuilder("/usr/bin/which", acpCmd).redirectErrorStream(true).start()
        val acpPath = which.inputStream.bufferedReader().readText().trim()
        which.waitFor()
        assumeTrue(acpPath.isNotBlank() && File(acpPath).exists(), "本机无 $acpCmd,跳过")
        val real = File(acpPath).toPath().toRealPath().toString()
        val distDir = File(real).parent
        val workDir = File.createTempFile("sbx_wd", "").apply { delete(); mkdirs() }
        try {
            val profile = SandboxProfile.render(workDir, SandboxTier.USER, acpCmd)
            assertTrue(
                profile.contains("(subpath ${'"'}$distDir${'"'})"),
                "exec 白名单必须放行 ACP 真身目录 $distDir,否则 agent 起不来"
            )
            if (real.contains("/node_modules")) {
                val nm = real.substring(0, real.indexOf("/node_modules") + "/node_modules".length)
                assertTrue(profile.contains("(subpath ${'"'}$nm${'"'})"), "exec 白名单应放行 node_modules 根 $nm")
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `回归 file-write 放行 isolated config 目录`() {
        // 锁住 2026-06-21 部署后用户 newSession 全部卡死的 bug:CLAUDE_CONFIG_DIR=config_isolated 是
        // workDir 的【兄弟目录】(不在其 subpath 内),claude-agent-acp 在 session/new 往这里写 session 状态。
        // 漏进 file-write 白名单 → 写被 deny → node 卡在 session/new 永不回响应 → newSession 卡死。
        val workDir = File.createTempFile("sbx_wd", "").apply { delete(); mkdirs() }
        // 兄弟目录:与 workDir 同父、不在其 subpath 内,精确复刻生产 ~/.quant_agents/config_isolated 拓扑。
        val configDir = File(workDir.parentFile, workDir.name + "_config_isolated").apply { mkdirs() }
        try {
            val withCfg = SandboxProfile.render(workDir, SandboxTier.USER, acpCmd, configDir)
            val cfgReal = configDir.toPath().toRealPath().toString()
            assertTrue(
                withCfg.contains("(subpath ${'"'}$cfgReal${'"'})"),
                "file-write 必须放行 isolated config 目录 $cfgReal,否则 newSession 写 config 被 deny 卡死"
            )
            // 不传 configDir(非 isolated)时不放行,不无故扩大写面。
            val noCfg = SandboxProfile.render(workDir, SandboxTier.USER, acpCmd, null)
            assertFalse(
                noCfg.contains("(subpath ${'"'}$cfgReal${'"'})"),
                "未传 configDir 时不应放行该目录"
            )
        } finally {
            workDir.deleteRecursively()
            configDir.deleteRecursively()
        }
    }

    @Test
    fun `render 含临时目录放行 TMPDIR 父目录在 file-write 内`() {
        // 不依赖 sandbox-exec,纯文本断言:JNA/JVM 临时目录必须在写白名单(否则取数挂)。
        val tmpdir = System.getenv("TMPDIR")
        assumeTrue(tmpdir != null && File(tmpdir).exists(), "无 TMPDIR,跳过")
        val workDir = File.createTempFile("sbx_wd", "").apply { delete(); mkdirs() }
        try {
            val profile = SandboxProfile.render(workDir, SandboxTier.BACKFILL, acpCmd)
            val darwinParent = File(tmpdir).toPath().toRealPath().parent.toString()
            assertTrue(
                profile.contains(darwinParent),
                "profile 的 file-write 应放行 TMPDIR 父目录 $darwinParent,否则 JNA 临时目录写失败"
            )
            // 网络绝不授 inbound/bind 授权语句(起 server 的真拦点);只断真 (allow ...) 语句,不误命中注释。
            assertFalse(profile.contains("(allow network-inbound"), "绝不该授 network-inbound")
            assertFalse(profile.contains("(allow network-bind"), "绝不该授 network-bind")
            assertTrue(profile.contains("(deny default)"), "必须 deny default")
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `档位只影响网络段 BACKFILL 端口白名单 USER 全放出站`() {
        val workDir = File.createTempFile("sbx_wd", "").apply { delete(); mkdirs() }
        try {
            val backfill = SandboxProfile.render(workDir, SandboxTier.BACKFILL, acpCmd)
            val user = SandboxProfile.render(workDir, SandboxTier.USER, acpCmd)

            // BACKFILL:端口白名单(443),不含全放出站语句。
            assertTrue(backfill.contains("(allow network-outbound (remote tcp \"*:443\"))"), "回填档应放行外网 443")
            assertFalse(
                backfill.lineSequence().any { it.trim() == "(allow network-outbound)" },
                "回填档不该全放出站(禁实时外网)"
            )

            // USER:全放出站,不再用端口白名单收窄。
            assertTrue(
                user.lineSequence().any { it.trim() == "(allow network-outbound)" },
                "放网客应全放出站(实时取数)"
            )

            // 两档都从不授 inbound/bind → 起不了 server。
            assertFalse(backfill.contains("(allow network-inbound"), "回填档绝不授 network-inbound")
            assertFalse(user.contains("(allow network-inbound"), "放网客绝不授 network-inbound")
            assertFalse(backfill.contains("(allow network-bind"), "回填档绝不授 network-bind")
            assertFalse(user.contains("(allow network-bind"), "放网客绝不授 network-bind")

            // 网络以外的段(exec/write/signal/file-read/fork)两档逐字相同 → 截断到第一处 network 行前比对。
            fun beforeNetwork(s: String) = s.substringBefore(";; ---- 网络")
            assertEquals(
                beforeNetwork(backfill), beforeNetwork(user),
                "档位只允许影响 network 一段,其余段两档必须逐字相同"
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `isEnabled OFF 恒关 非 OFF 才开`() {
        assertFalse(SandboxProfile.isEnabled(SandboxTier.OFF), "OFF(无沙箱)必须关沙箱")
        // BACKFILL/USER 时是否开取决于本机有无 sandbox-exec;此处只断言 OFF 短路,不依赖平台。
    }

    @Test
    fun `disable 开关关闭沙箱`() {
        val prev = System.getProperty("quant.agent.sandbox.disable")
        System.setProperty("quant.agent.sandbox.disable", "true")
        try {
            assertFalse(SandboxProfile.isEnabled(SandboxTier.BACKFILL), "-Dquant.agent.sandbox.disable=true 应关回填档")
            assertFalse(SandboxProfile.isEnabled(SandboxTier.USER), "-Dquant.agent.sandbox.disable=true 应关放网客")
        } finally {
            if (prev != null) System.setProperty("quant.agent.sandbox.disable", prev)
            else System.clearProperty("quant.agent.sandbox.disable")
        }
    }

    /**
     * 链路一致性核心证明(验收场景 K):
     *  - guard 健康态(ARMED) → isEnabled 与升级前逐字相同(平台有 sandbox-exec 时为 true)
     *  - guard TRIPPED → isEnabled 返回 false(等价 OFF 态),且 render 输出的 profile 文本【一字不受 guard 影响】
     */
    @Test
    fun `guard TRIP 关沙箱 但 render profile 一字不改`() {
        assumeTrue(sandboxAvailable(), "本机无 sandbox-exec,跳过")
        val (workDir, projectRoot, _) = setup(SandboxTier.USER)
        try {
            // render 是纯函数,与 guard 状态无关 → TRIP 前后 profile 文本逐字节相同
            val before = SandboxProfile.render(workDir, SandboxTier.USER, acpCmd)
            SandboxRolloutGuard.forceTrip(SandboxTier.USER)
            try {
                assertFalse(SandboxProfile.isEnabled(SandboxTier.USER), "guard TRIPPED 应让 isEnabled 返回 false(等价 OFF)")
                val after = SandboxProfile.render(workDir, SandboxTier.USER, acpCmd)
                assertEquals(before, after, "render 是纯函数,guard 状态绝不影响 profile 文本(profile 不重写承诺)")
            } finally {
                SandboxRolloutGuard.rearm(SandboxTier.USER)
            }
            // rearm 后恒等返回 → isEnabled 行为与升级前一致
            assertTrue(SandboxProfile.isEnabled(SandboxTier.USER), "guard re-ARMED 后 isEnabled 应恢复(本机有 sandbox-exec)")
        } finally {
            cleanup(projectRoot, workDir)
        }
    }
}
