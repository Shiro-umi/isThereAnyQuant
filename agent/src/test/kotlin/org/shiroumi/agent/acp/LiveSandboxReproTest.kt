package org.shiroumi.agent.acp

import kotlinx.coroutines.*
import kotlin.test.Test

/**
 * 线上链路一致性复现:用真实 AcpClient(线上同一个类、同一套 StdioTransport/Protocol/Client),
 * 在沙箱 USER 档 + kimi env 下,串行与并发跑 newSession,看是否复现"newSession 卡死"。
 *
 * 默认 @Ignore(连真 LLM、需本机 sandbox-exec、消耗 token),手动 -DrunLiveSandbox=true 跑。
 */
class LiveSandboxReproTest {

    private val workDir =
        System.getProperty("liveSandboxWorkDir")
            ?: (System.getProperty("user.home") + "/.quant_agents/live_repro_test")
    // 密钥绝不硬编码进仓:手动跑时用 -DkimiToken=sk-kimi-xxx 传入(配合 -DrunLiveSandbox=true)。
    private val kimiToken = System.getProperty("kimiToken") ?: ""

    private fun cfg(tier: SandboxTier, configDir: String) = AcpClient.Config(
        workDir = workDir,
        isolated = true,
        sandboxTier = tier,
        apiKey = kimiToken,
        configDir = configDir,
        modelId = "kimi-for-coding",
        baseUrl = "https://api.kimi.com/coding",
        provider = "kimi",
    )

    private suspend fun timed(label: String, block: suspend () -> String): Pair<String, Long> {
        val t0 = System.currentTimeMillis()
        val r = block()
        val dt = System.currentTimeMillis() - t0
        println(">>> $label took ${dt}ms -> $r")
        return r to dt
    }

    /**
     * 场景A(根因决定性实验,不烧 token、不连 LLM):制造 launch/initialize 握手卡死,
     * 验证 workflow 改判——线上 3m47s 真卡点是否在 client.initialize() 握手(launch),
     * 以及该卡点是否落在 newSession 的 withTimeout 作用域之外(=只给 newSession 加超时打错靶)。
     *
     * 手法:claudeCommand 指向 /bin/cat —— 它被 spawn 后只静默读 stdin、永不回 ACP 握手帧,
     * AcpClient.initialize() 的 client.initialize() 会永久阻塞在等待 protocolVersion 响应。
     * 这精确模拟"子进程起来了但 ACP 握手不返回"。
     */
    @Test
    fun scenarioA_launchHandshakeHang() = runBlocking {
        if (System.getProperty("runLiveSandbox") != "true") {
            println("skip: set -DrunLiveSandbox=true to run"); return@runBlocking
        }
        java.io.File(workDir).mkdirs()
        val client = AcpClient()
        val cfg = AcpClient.Config(
            claudeCommand = "/bin/cat",          // 静默读 stdin,永不回 ACP 握手
            workDir = workDir,
            isolated = true,
            sandboxTier = SandboxTier.USER,
            apiKey = kimiToken,
            configDir = "$workDir/.claude-isolated",
            preferZedAcpAgent = false,           // 强制用上面的 claudeCommand
        )
        // 关键断言:initialize(=launch) 套 withTimeoutOrNull 是否能被打断?
        val r = withTimeoutOrNull(8_000) {
            client.initialize(cfg)
            "INIT-RETURNED"
        }
        println(">>> scenarioA initialize withTimeout(8s) result = ${r ?: "TIMEOUT(被打断,说明 launch 可被 withTimeout 兜底)"}")
        // 善后:把卡住的子进程树杀掉
        runCatching { withTimeoutOrNull(8_000) { client.shutdown() } }
        // 结论:若 r==null 即 withTimeout 成功打断 initialize → step3 给 bridge.launch 套 withTimeout 可行;
        //       若 initialize 自身吞掉了取消(r 非 null 或 shutdown 卡死)→ 需要更强的 OS 级兜底(step1)。
        check(true)
    }

    /**
     * 场景A2(根因决定性实验,不烧 token):复现线上【精确时序】——initialize 成功、newSession 卡死,
     * 验证 newSession 外层 withTimeout(10s) 是否真能打断它。
     *
     * fake-acp.sh:回 initialize 响应、对 session/new 静默吞掉不回。
     * 用 OFF 档(测的是 AcpClient/withTimeout 协程取消语义,与沙箱正交;fake 脚本在 /tmp 不进沙箱 exec 白名单)。
     *
     * 若 withTimeout(10s) 打断 newSession(r==null) → 线上 newSession 若真卡,withTimeout 本应触发
     *   → 反证"卡点在 newSession",指向卡点在【无超时的 launch】(印证 workflow 改判)。
     * 若 withTimeout 打不断 newSession → 卡点可能确在 newSession 且取消失效,需 OS 级兜底。
     */
    @Test
    fun scenarioA2_newSessionHangWithTimeout() = runBlocking {
        if (System.getProperty("runLiveSandbox") != "true") {
            println("skip: set -DrunLiveSandbox=true to run"); return@runBlocking
        }
        val fake = "/tmp/fake-acp.sh"
        if (!java.io.File(fake).canExecute()) { println("skip: $fake not executable"); return@runBlocking }
        java.io.File(workDir).mkdirs()
        val client = AcpClient()
        val cfg = AcpClient.Config(
            claudeCommand = fake,
            workDir = workDir,
            isolated = false,                    // fake 不认 --setting-sources 等参,关隔离参数
            sandboxTier = SandboxTier.OFF,       // 测协程取消语义,与沙箱正交
            apiKey = kimiToken,
            preferZedAcpAgent = false,
        )
        // initialize 应秒级成功(fake 回 initialize)
        val initR = withTimeoutOrNull(8_000) { client.initialize(cfg); "INIT-OK" }
        println(">>> scenarioA2 initialize = ${initR ?: "INIT-TIMEOUT(fake 没回?)"}")
        check(initR == "INIT-OK") { "fake 应回 initialize" }
        // newSession 应卡死(fake 吞 session/new);验证 withTimeout(10s) 能否打断
        val t0 = System.currentTimeMillis()
        val nsR = withTimeoutOrNull(10_000) { client.newSession(workDir) }
        val dt = System.currentTimeMillis() - t0
        println(">>> scenarioA2 newSession withTimeout(10s) = ${nsR ?: "TIMEOUT(被打断)"} after ${dt}ms")
        println(">>> 裁决: ${if (nsR == null) "withTimeout 能打断 newSession → 线上若卡 newSession 本应触发超时日志 → 反证卡点在无超时的 launch" else "withTimeout 打不断 newSession → 取消失效,需 OS 级兜底"}")
        runCatching { withTimeoutOrNull(8_000) { client.shutdown() } }
        check(true)
    }

    @Test
    fun reproSerialAndConcurrent() = runBlocking {
        if (System.getProperty("runLiveSandbox") != "true") {
            println("skip: set -DrunLiveSandbox=true to run"); return@runBlocking
        }
        java.io.File(workDir).mkdirs()

        // === 场景1: USER 档 + 正常 configDir,串行 1 个 newSession ===
        run {
            val client = AcpClient()
            client.initialize(cfg(SandboxTier.USER, "$workDir/.claude-isolated"))
            val (sid, dt) = timed("S1 USER serial newSession") {
                withTimeoutOrNull(15_000) { client.newSession(workDir) } ?: "TIMEOUT(15s)"
            }
            check(sid != "TIMEOUT(15s)") { "S1 newSession 卡死!" }
            client.shutdown()
        }

        // === 场景2: USER 档 + 共享 client,并发 4 个 newSession(复刻线上 4 连接) ===
        run {
            val client = AcpClient()
            client.initialize(cfg(SandboxTier.USER, "$workDir/.claude-isolated"))
            val results = (1..4).map { i ->
                async(Dispatchers.IO) {
                    timed("S2 concurrent newSession #$i") {
                        withTimeoutOrNull(15_000) { client.newSession(workDir) } ?: "TIMEOUT(15s)"
                    }
                }
            }.awaitAll()
            val timeouts = results.count { it.first == "TIMEOUT(15s)" }
            println(">>> S2 concurrent: ${results.size - timeouts}/${results.size} ok, $timeouts timeout")
            check(timeouts == 0) { "S2 并发 newSession 有 $timeouts 个卡死!" }
            client.shutdown()
        }

        // === 场景3: USER 档,跑通 newSession -> prompt(整条线上链路含取数工具) ===
        run {
            val client = AcpClient()
            client.initialize(cfg(SandboxTier.USER, "$workDir/.claude-isolated"))
            val sid = withTimeoutOrNull(15_000) { client.newSession(workDir) } ?: "TIMEOUT"
            check(sid != "TIMEOUT") { "S3 newSession 卡死!" }
            println(">>> S3 newSession ok=$sid, sending prompt...")
            val flow = client.prompt(sid, "用一句话回答:1+1 等于几?")
            val deadline = System.currentTimeMillis() + 60_000
            var gotEvent = false
            withTimeoutOrNull(60_000) {
                flow.collect { ev ->
                    gotEvent = true
                    println(">>> S3 event: ${ev::class.simpleName}")
                }
            }
            println(">>> S3 prompt gotEvent=$gotEvent")
            client.shutdown()
        }

        // === 场景4: 复刻线上畸形 configDir(~ 未展开),真实 AcpClient 并发 4 连接 ===
        run {
            val badConfigDir = "/Users/zhouzheng/Code/quant/ktor-server/build/deploy.release/~/.quant_agents/config_isolated"
            val client = AcpClient()
            client.initialize(cfg(SandboxTier.USER, badConfigDir))
            val results = (1..4).map { i ->
                async(Dispatchers.IO) {
                    timed("S4 badConfigDir concurrent newSession #$i") {
                        withTimeoutOrNull(15_000) { client.newSession(workDir) } ?: "TIMEOUT(15s)"
                    }
                }
            }.awaitAll()
            val timeouts = results.count { it.first == "TIMEOUT(15s)" }
            println(">>> S4 badConfigDir concurrent: ${results.size - timeouts}/${results.size} ok, $timeouts timeout")
            check(timeouts == 0) { "S4 畸形 configDir 并发 newSession 有 $timeouts 个卡死!" }
            client.shutdown()
        }

        println(">>> ALL SCENARIOS PASSED")
    }
}
