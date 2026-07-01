package org.shiroumi.server.websocket.service

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回测 agent 应用层隔离（L2）契约单测。
 *
 * 覆盖：
 *  - skill 源按 mode 选取（回测→agent-backtest-skills 且含回测 skill；实盘→agent-analysis-skills 不含回测 skill）
 *  - 回测版 CLAUDE.md 含关键约束句、不含实时取数段
 *  - 回测 settings.json 含全部 permissions.deny 项且可写入磁盘
 *  - CLI 工具集按 mode 选取（回测三件套指向 *-asof 且不含 market-emotion/研报）
 */
class BacktestAgentProvisioningTest {

    private fun tempProjectRoot(): File {
        val root = Files.createTempDirectory("quant-l2-test").toFile()
        root.deleteOnExit()
        // 模拟两套 skill 源目录布局
        File(root, "private/agent-backtest-skills/entry-exit-analysis-backtest").mkdirs()
        File(root, "private/agent-analysis-skills/entry-exit-analysis").mkdirs()
        return root
    }

    @Test
    fun `回测模式 skill 源是 agent-backtest-skills 且含回测 skill`() {
        val root = tempProjectRoot()
        val candidates = BacktestAgentProvisioning.skillSourceCandidates(root, backtestMode = true)
        val baseDir = candidates.firstOrNull { it.isDirectory }
        assertTrue(baseDir != null)
        assertTrue(baseDir!!.path.endsWith("private/agent-backtest-skills"))
        // 回测源含回测 skill，不含实盘 skill
        val skillNames = baseDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertTrue("entry-exit-analysis-backtest" in skillNames)
        assertFalse("entry-exit-analysis" in skillNames)
    }

    @Test
    fun `实盘模式 skill 源是 agent-analysis-skills 且不含回测 skill`() {
        val root = tempProjectRoot()
        val candidates = BacktestAgentProvisioning.skillSourceCandidates(root, backtestMode = false)
        val baseDir = candidates.firstOrNull { it.isDirectory }
        assertTrue(baseDir != null)
        assertTrue(baseDir!!.path.endsWith("private/agent-analysis-skills"))
        val skillNames = baseDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertTrue("entry-exit-analysis" in skillNames)
        assertFalse("entry-exit-analysis-backtest" in skillNames)
    }

    @Test
    fun `实盘模式 skill 源缺首选时回退第二候选`() {
        val root = Files.createTempDirectory("quant-l2-fallback").toFile()
        root.deleteOnExit()
        // 只建回退目录 agent/analysis-skills
        File(root, "agent/analysis-skills/trend-analysis").mkdirs()
        val candidates = BacktestAgentProvisioning.skillSourceCandidates(root, backtestMode = false)
        val baseDir = candidates.firstOrNull { it.isDirectory }
        assertTrue(baseDir != null)
        assertTrue(baseDir!!.path.endsWith("agent/analysis-skills"))
    }

    @Test
    fun `回测版 CLAUDE_md 含关键约束句`() {
        val md = BacktestAgentProvisioning.backtestClaudeMd(skillIndexMarkdown = "（skill 索引占位）")
        // 当前是回测模式
        assertTrue(md.contains("当前是回测模式"))
        // 唯一产物是决策 JSON / out/decisions
        assertTrue(md.contains("out/decisions/{执行日}.json"))
        assertTrue(md.contains("trade-intent"))
        assertTrue(md.contains("\"side\": \"BUY\""))
        assertTrue(md.contains("\"hint\": \"LIMIT\""))
        assertTrue(md.contains("limitPrice"))
        // 每只票必给买点价、不允许无买点
        assertTrue(md.contains("都必须给出一个主推买点入场价"))
        assertTrue(md.contains("不允许对任何股票输出"))
        // 分钟线截到信号日 T 收盘（防未来函数口径）
        assertTrue(md.contains("分钟线统一截到信号日 T 收盘"))
        // 残留风险声明
        assertTrue(md.contains("未实现 OS 级文件系统沙盒与进程网络层硬禁断"))
    }

    @Test
    fun `回测版 CLAUDE_md 不含实时取数段`() {
        val md = BacktestAgentProvisioning.backtestClaudeMd(skillIndexMarkdown = "x")
        // 不挂 market-emotion 工具说明段（### market-emotion 标题不出现）
        assertFalse(md.contains("### market-emotion"))
        // 不要求 quant-header / quant-kline / quant-market-sentiment 渲染块作为产物
        assertFalse(md.contains("```quant-header"))
        assertFalse(md.contains("```quant-kline"))
        // 明确禁实时
        assertTrue(md.contains("严禁读取实时价格"))
    }

    @Test
    fun `回测 settings_json 含全部 deny 项`() {
        val json = BacktestAgentProvisioning.backtestSettingsJson()
        assertTrue(json.contains("\"WebFetch\""))
        assertTrue(json.contains("\"WebSearch\""))
        assertTrue(json.contains("\"Bash(curl:*)\""))
        assertTrue(json.contains("\"Bash(wget:*)\""))
        assertTrue(json.contains("\"mcp__chrome-devtools__*\""))
        assertTrue(json.contains("\"deny\""))
    }

    @Test
    fun `实盘 settings_json deny 内置写工具且 Bash 不进 deny`() {
        val json = BacktestAgentProvisioning.liveSettingsJson()
        // 禁全部内置写盘工具（裸名 deny 把工具从 claude 上下文彻底移除）
        assertTrue(json.contains("\"Write\""))
        assertTrue(json.contains("\"Edit\""))
        assertTrue(json.contains("\"MultiEdit\""))
        assertTrue(json.contains("\"NotebookEdit\""))
        assertTrue(json.contains("\"deny\""))
        // 关键：Bash 绝不能进 deny。裸 Bash deny 会让所有更具体的 Bash(./xxx:*) allow 失效（取数全断）。
        // deny 段（allow 之前）内不应出现裸 Bash。
        val denySegment = json.substringAfter("\"deny\"").substringBefore("\"allow\"")
        assertFalse(denySegment.contains("\"Bash\""))
    }

    @Test
    fun `writeLiveLocalSettingsJson 覆盖 local 为零 allow 零 deny 堵死旁路`() {
        // claude CLI 会把历史 always-allow 命令持久化进 settings.local.json，local 命中即放行会架空 project 层
        // dontAsk。provisioning 每次会话把 local 覆盖为空 allow。验证：即便 local 原先有宽 allow，覆盖后归零。
        val workDir = Files.createTempDirectory("quant-local-test").toFile().also { it.deleteOnExit() }
        val claudeDir = File(workDir, ".claude").also { it.mkdirs() }
        // 预置一份"污染" local，模拟历史 695 条 allow 里的危险项
        File(claudeDir, "settings.local.json")
            .writeText("""{"permissions":{"allow":["Bash(dscl . list /Users)","Bash(ls)"]}}""")

        val written = BacktestAgentProvisioning.writeLiveLocalSettingsJson(workDir.absolutePath)

        assertEquals(File(claudeDir, "settings.local.json").absolutePath, written.absolutePath)
        val permissions = Json.parseToJsonElement(written.readText()).jsonObject.getValue("permissions").jsonObject
        // allow / deny 都归零，污染项被清除
        assertTrue(permissions.getValue("allow").jsonArray.isEmpty())
        assertTrue(permissions.getValue("deny").jsonArray.isEmpty())
        assertFalse(written.readText().contains("dscl"))
    }

    @Test
    fun `writeLiveLocalSettingsJson 写盘失败向上抛异常不静默吞掉`() {
        // 收口 fail-fast 前提：写空 local 失败必须向上传播（由 AgentWebSocketService 包成
        // SecuritySettingsProvisionException 中止会话），不能在方法内被吞导致旧污染 local 残留放行。
        // 用一个"workDir 路径本身是普通文件"制造 .claude mkdirs/writeText 失败。
        val notADir = Files.createTempFile("quant-local-notadir", ".tmp").toFile().also { it.deleteOnExit() }
        assertFailsWith<Exception> {
            BacktestAgentProvisioning.writeLiveLocalSettingsJson(notADir.absolutePath)
        }
    }

    @Test
    fun `实盘 settings_json defaultMode 必须嵌在 permissions 内且为 dontAsk`() {
        // 收口生效的硬前提：claude-agent-acp 适配器与底层 claude 引擎都只读 permissions.defaultMode。
        // 写在顶层会被读成 undefined、回退 default 模式，deny-by-default 收口静默失效。用结构化解析钉死层级，
        // 不用脆弱的 contains（顶层与内层的 defaultMode 字符串 contains 无法区分）。
        val root = Json.parseToJsonElement(BacktestAgentProvisioning.liveSettingsJson()).jsonObject
        // 顶层不得有 defaultMode
        assertFalse("defaultMode" in root)
        assertEquals("dontAsk", root.getValue("permissions").jsonObject.getValue("defaultMode").jsonPrimitive.content)
    }

    @Test
    fun `实盘 settings_json 用 dontAsk 默认拒并精确 allow 6 取数 CLI 与 bc 与读工具与联网`() {
        val json = BacktestAgentProvisioning.liveSettingsJson()
        // defaultMode=dontAsk：未命中 allow 的工具自动拒绝、不弹 ask（无人审批的 ACP 场景刚需）
        assertTrue(json.contains("\"defaultMode\""))
        assertTrue(json.contains("\"dontAsk\""))
        assertTrue(json.contains("\"allow\""))
        // 6 个取数 CLI（语义对齐 CommandWhitelist.LIVE）
        assertTrue(json.contains("\"Bash(./get-candles:*)\""))
        assertTrue(json.contains("\"Bash(./get-intraday-candles:*)\""))
        assertTrue(json.contains("\"Bash(./get-research-reports:*)\""))
        assertTrue(json.contains("\"Bash(./get-industry-research-reports:*)\""))
        assertTrue(json.contains("\"Bash(./get-limit-list:*)\""))
        assertTrue(json.contains("\"Bash(./market-emotion:*)\""))
        // bc 形态
        assertTrue(json.contains("\"Bash(echo:*)\""))
        // 报告链路所需读工具（读 SKILL.md 学报告块格式）
        assertTrue(json.contains("\"Read\""))
        assertTrue(json.contains("\"Glob\""))
        assertTrue(json.contains("\"Grep\""))
        // 交互保留实时取数（区别于回测禁联网）
        assertTrue(json.contains("\"WebSearch\""))
        assertTrue(json.contains("\"WebFetch\""))
    }

    @Test
    fun `实盘 settings_json 不照搬回测禁联网 deny`() {
        val json = BacktestAgentProvisioning.liveSettingsJson()
        // 实盘 deny 段不应出现 WebFetch/WebSearch（它们在 allow 中保留实时取数）
        val denySegment = json.substringAfter("\"deny\"").substringBefore("\"allow\"")
        assertFalse(denySegment.contains("WebFetch"))
        assertFalse(denySegment.contains("WebSearch"))
    }

    @Test
    fun `写实盘 settings_json 落盘到 dotclaude 目录`() {
        val workDir = Files.createTempDirectory("quant-l2-live-settings").toFile()
        workDir.deleteOnExit()
        val file = BacktestAgentProvisioning.writeLiveSettingsJson(workDir.absolutePath)
        assertEquals(File(workDir, ".claude/settings.json").absolutePath, file.absolutePath)
        assertTrue(file.exists())
        val content = file.readText()
        assertTrue(content.contains("\"Write\""))
        assertTrue(content.contains("\"Bash(./get-candles:*)\""))
    }

    @Test
    fun `写回测 settings_json 落盘到 dotclaude 目录`() {
        val workDir = Files.createTempDirectory("quant-l2-settings").toFile()
        workDir.deleteOnExit()
        val file = BacktestAgentProvisioning.writeBacktestSettingsJson(workDir.absolutePath)
        assertEquals(File(workDir, ".claude/settings.json").absolutePath, file.absolutePath)
        assertTrue(file.exists())
        val content = file.readText()
        assertTrue(content.contains("WebFetch"))
        assertTrue(content.contains("mcp__chrome-devtools__*"))
    }

    @Test
    fun `回测 CLI 工具集只含历史取数三件套且指向 asof`() {
        val tools = BacktestAgentProvisioning.cliTools(backtestMode = true)
        val names = tools.map { it.toolName }
        // toolName 保持不变
        assertEquals(listOf("get-candles", "get-intraday-candles", "get-limit-list"), names)
        // 产物指向 *-asof
        assertTrue(tools.all { it.installRelativePath.contains("-asof") })
        assertTrue(tools.all { it.gradleTask.contains("-asof") })
        // 不含 market-emotion / 研报
        assertFalse("market-emotion" in names)
        assertFalse("get-research-reports" in names)
        assertFalse("get-industry-research-reports" in names)
    }

    @Test
    fun `回测模式终态跳过落库 实盘模式允许落库`() {
        // handleStatusTerminal 据此跳过 AgentAnalysisResultRepository.save。
        assertFalse(BacktestAgentProvisioning.shouldPersistOnCompleted(backtestMode = true))
        assertTrue(BacktestAgentProvisioning.shouldPersistOnCompleted(backtestMode = false))
    }

    @Test
    fun `实盘 CLI 工具集维持原 6 件套裸 spec`() {
        val tools = BacktestAgentProvisioning.cliTools(backtestMode = false)
        val names = tools.map { it.toolName }
        assertEquals(
            listOf(
                "get-candles",
                "get-intraday-candles",
                "get-research-reports",
                "get-industry-research-reports",
                "get-limit-list",
                "market-emotion",
            ),
            names,
        )
        // 实盘产物不带 -asof 后缀
        assertTrue(tools.none { it.installRelativePath.contains("-asof") })
    }
}
