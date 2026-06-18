package org.shiroumi.server.websocket.service

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
