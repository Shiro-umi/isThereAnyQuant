package org.shiroumi.cli.batch

import java.io.File
import org.shiroumi.agent.impl.SkillManager

/**
 * 回测 batch driver 的 agent 工作空间装配纯逻辑。
 *
 * 业务背景：生产链路里同一套隔离装配由 ktor-server 的 `BacktestAgentProvisioning`（internal）承担，
 * 在 WebSocket 建会话时给 agent 布 skill 软链、CLAUDE.md、禁联网 settings.json、历史取数工具 wrapper。
 * 本驱动跑在 cli 模块，按 KMP 模块依赖边界 cli 不允许依赖 ktor-server，因此把这段「应用层隔离装配」逻辑
 * 在 cli 侧等价落地（与 ktor-server 同口径），不破坏边界、也不缩水。
 *
 * 与生产装配的唯一差异：
 *  - wrapper 在 exec 历史取数二进制前写死注入 `--as-of={信号日T}`，把每个信号日的工作空间锁死在该信号日，
 *    agent 命令里不带任何日期参数（由回测命令白名单约束），宿主负责注入。这样取数严格只能看到信号日 T 及之前。
 *
 * 边界纪律：本对象只产出文件与软链，不触碰 agent 会话生命周期。
 */
internal object BatchAgentProvisioning {

    /** 回测 skill 源目录（相对项目根），与 ktor-server BacktestAgentProvisioning.BACKTEST_SKILLS_REL 一致。 */
    private const val BACKTEST_SKILLS_REL: String = "private/agent-backtest-skills"

    /**
     * 回测模式 CLI 工具集：仅历史取数三件套，toolName 保持不变，installRelativePath 指向 as-of 历史取数产物。
     *
     * market-emotion（无历史快照）与研报工具不进回测集，与生产回测 agent 一致。
     */
    private val BACKTEST_TOOLS: List<CliToolSpec> = listOf(
        CliToolSpec(
            toolName = "get-candles",
            installRelativePath = "tools/get-candles-asof/build/install/get-candles-asof/bin/get-candles-asof",
            gradleTask = ":tools:get-candles-asof:installDist",
        ),
        CliToolSpec(
            toolName = "get-intraday-candles",
            installRelativePath = "tools/get-intraday-candles-asof/build/install/get-intraday-candles-asof/bin/get-intraday-candles-asof",
            gradleTask = ":tools:get-intraday-candles-asof:installDist",
        ),
        CliToolSpec(
            toolName = "get-limit-list",
            installRelativePath = "tools/get-limit-list-asof/build/install/get-limit-list-asof/bin/get-limit-list-asof",
            gradleTask = ":tools:get-limit-list-asof:installDist",
        ),
    )

    /** 历史取数 CLI 工具规格。 */
    internal data class CliToolSpec(
        val toolName: String,
        val installRelativePath: String,
        val gradleTask: String,
    )

    /** 装配结果汇总，供驱动打印诊断。 */
    internal data class ProvisionResult(
        val workDir: File,
        val linkedSkills: Int,
        val wrappedTools: List<String>,
        val missingTools: List<String>,
    )

    /**
     * 为某个信号日布置一个隔离的回测 agent 工作空间。
     *
     * @param projectRoot 项目根目录（含 private agent-backtest-skills 与 as-of 历史取数产物）
     * @param workDir 该信号日专属工作空间（如 ~/.quant_backtest_agents/{runId}/{signalDate}/）
     * @param signalDateYyyymmdd 信号日 T 的 YYYYMMDD（写死注入到 wrapper 的 --as-of）
     * @param serverPort 历史取数工具回连的 Ktor server 端口（as-of 历史取数工具走 HTTP 取数）
     */
    fun provision(
        projectRoot: File,
        workDir: File,
        signalDateYyyymmdd: String,
        serverPort: Int,
    ): ProvisionResult {
        workDir.mkdirs()
        File(workDir, "out/decisions").mkdirs()

        val linkedSkills = linkSkills(projectRoot, workDir)
        writeClaudeMd(workDir)
        writeSettingsJson(workDir)
        val (wrapped, missing) = writeToolWrappers(projectRoot, workDir, signalDateYyyymmdd, serverPort)

        return ProvisionResult(
            workDir = workDir,
            linkedSkills = linkedSkills,
            wrappedTools = wrapped,
            missingTools = missing,
        )
    }

    /** 软链 skill 源目录下每个 skill 到 {workDir}/.claude/skills/，清空重建，与生产同口径。 */
    private fun linkSkills(projectRoot: File, workDir: File): Int {
        val baseSkillsDir = File(projectRoot, BACKTEST_SKILLS_REL).takeIf { it.isDirectory }
        val userSkillsDir = File(workDir, ".claude/skills")
        userSkillsDir.mkdirs()

        // 清理旧沙盒（含旧软链接、旧目录、旧文件）。
        userSkillsDir.listFiles()?.forEach { entry ->
            when {
                java.nio.file.Files.isSymbolicLink(entry.toPath()) -> entry.delete()
                entry.isDirectory -> entry.deleteRecursively()
                else -> entry.delete()
            }
        }

        if (baseSkillsDir == null) return 0
        var linked = 0
        baseSkillsDir.listFiles()?.forEach { skillDir ->
            if (skillDir.isDirectory) {
                val symlinkPath = File(userSkillsDir, skillDir.name).toPath()
                java.nio.file.Files.createSymbolicLink(symlinkPath, skillDir.toPath())
                linked++
            }
        }
        return linked
    }

    /** 写回测版 CLAUDE.md，内容与 ktor-server BacktestAgentProvisioning.backtestClaudeMd 等价。 */
    private fun writeClaudeMd(workDir: File) {
        val skillIndexMarkdown = SkillManager(workDir.absolutePath)
            .discoverDiagnostics()
            .sortedBy { it.name }
            .joinToString("\n") { "- `${it.name}`: `${it.path}`" }
        File(workDir, "CLAUDE.md").writeText(backtestClaudeMd(skillIndexMarkdown))
    }

    /** 写禁联网 settings.json（permissions.deny），与生产同口径。 */
    private fun writeSettingsJson(workDir: File) {
        val claudeDir = File(workDir, ".claude").also { it.mkdirs() }
        File(claudeDir, "settings.json").writeText(
            """
            {
              "permissions": {
                "deny": [
                  "WebFetch",
                  "WebSearch",
                  "Bash(curl:*)",
                  "Bash(wget:*)",
                  "mcp__chrome-devtools__*"
                ]
              }
            }
            """.trimIndent()
        )
    }

    /**
     * 为历史取数三件套写 wrapper：exec *-asof 二进制前写死注入 `--as-of={信号日T}`。
     *
     * agent 调用 `./get-candles --code 000001.SZ`（命令白名单禁日期参数），wrapper 把 `--as-of` 补在前面，
     * 服务端据此截断历史表（trade_date <= as_of），杜绝看到信号日之后的任何数据（防未来函数的宿主侧约束）。
     *
     * @return (已写 wrapper 的工具名, 缺失产物的工具名)
     */
    private fun writeToolWrappers(
        projectRoot: File,
        workDir: File,
        signalDateYyyymmdd: String,
        serverPort: Int,
    ): Pair<List<String>, List<String>> {
        val wrapped = mutableListOf<String>()
        val missing = mutableListOf<String>()
        BACKTEST_TOOLS.forEach { spec ->
            val toolSource = File(projectRoot, spec.installRelativePath)
            val toolLauncher = File(workDir, spec.toolName)
            if (java.nio.file.Files.isSymbolicLink(toolLauncher.toPath()) || toolLauncher.exists()) {
                toolLauncher.delete()
            }
            if (toolSource.exists()) {
                writeAsOfWrapperScript(toolLauncher, toolSource.absolutePath, serverPort, signalDateYyyymmdd)
                wrapped += spec.toolName
            } else {
                missing += spec.toolName
            }
        }
        return wrapped to missing
    }

    /**
     * 写带 `--as-of` 注入的 wrapper 脚本。
     *
     * 防御性去重：若 agent 误传了 `--as-of`，先剔除再由宿主统一注入，确保信号日只由宿主锁定。
     */
    private fun writeAsOfWrapperScript(
        launcherFile: File,
        targetBinPath: String,
        serverPort: Int,
        signalDateYyyymmdd: String,
    ) {
        launcherFile.writeText(
            """
            |#!/usr/bin/env bash
            |export QUANT_SERVER_PORT=$serverPort
            |# 宿主写死信号日 T，agent 无法绕过：先剔除可能误传的 --as-of，再统一注入。
            |args=()
            |skip_next=false
            |for arg in "${'$'}@"; do
            |  if [[ "${'$'}skip_next" == "true" ]]; then skip_next=false; continue; fi
            |  case "${'$'}arg" in
            |    --as-of) skip_next=true; continue ;;
            |    --as-of=*) continue ;;
            |  esac
            |  args+=("${'$'}arg")
            |done
            |exec ${shellQuote(targetBinPath)} --as-of=$signalDateYyyymmdd "${'$'}{args[@]}"
            """.trimMargin()
        )
        launcherFile.setExecutable(true)
    }

    /** 单引号包裹路径，转义内部单引号，防止路径含空格/特殊字符。 */
    private fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /**
     * 回测版 CLAUDE.md 内容，与 ktor-server BacktestAgentProvisioning.backtestClaudeMd 字面对齐。
     *
     * 关键约束：禁实时取数、禁联网、取数走 as-of（宿主注入，agent 不写日期）、
     * 唯一产物是 out 决策目录下的买点 JSON、每只票必给买点价、不入库。
     *
     * 与生产唯一差异：产物文件名改为 `out/decisions/{执行日}-{ts_code}.json`（每只票一份），
     * 因为本驱动一次会话只分析一只票、并发多会话共用同一信号日工作空间，单文件会互相覆盖；
     * 由宿主在会话全部完成后把同信号日各单票文件合并为 `{执行日}.json` 写到统一买点价目录。
     */
    private fun backtestClaudeMd(skillIndexMarkdown: String): String =
        """
        # 角色设定
        你是一名量化回测信号 Agent。你在信号日 T 盘后对单只候选股票做纯历史 K 线结构分析，为它产出一个主推买点入场价。

        ## 运行模式：回测（绝对约束）
        1. 当前是回测模式。所有数据都是信号日 T 及之前的历史数据。
        2. 严禁读取实时价格、实时行情、实时市场情绪。没有 market-emotion 工具。
        3. 严禁联网：禁止网页抓取、网页搜索、curl、wget。这些能力已被禁用。
        4. 唯一产物是一份决策 JSON 文件。不写任何 Markdown 报告，不输出 quant-header、quant-kline、quant-market-sentiment 等任何渲染块。

        ## Claude Code Skills
        当前工作目录已挂载回测专用 skills，由 Claude Code 原生机制发现和触发。
        触发买卖点判断时，读取并遵守对应 SKILL.md 的回测约束。
        如果需要手动读取 Skill 文件，只能读取下面列出的相对路径，不要使用绝对路径或 `../`：

        $skillIndexMarkdown

        数学计算不是 Skill。所有数学计算统一使用 shell 命令 `bc`。

        ## 可用 CLI 工具（仅历史取数，as-of 已由宿主锁定）
        以下工具已存在于工作目录中，可直接调用。
        取数日期由宿主写死为信号日 T，你不需要、也禁止追加任何日期类参数（如 --as-of、--start-date、--end-date、--trade-date）。
        涉及股票代码参数时，统一传带交易所后缀的 ts_code（如 `000001.SZ`、`600519.SH`、`430047.BJ`）。

        ### get-candles
        **用途**：获取指定股票截至信号日 T 的历史日K线数据（前复权）。
        **参数**：
        - `--code, -c`：股票代码（带后缀 ts_code）
        - `--name, -n`：股票名称（精确匹配）
        - `--limit, -l`：获取天数（默认 60）
        **约束**：--code 和 --name 二选一必填。禁止追加日期参数。
        **用法示例**：
        ```bash
        ./get-candles --code 000001.SZ
        ./get-candles --code 000001.SZ --limit 90
        ```

        ### get-intraday-candles
        **用途**：获取指定股票截至信号日 T 收盘的历史小周期K线数据（60min/30min/15min/5min）。
        **参数**：
        - `--code, -c`：股票代码（带后缀 ts_code）
        - `--name, -n`：股票名称（精确匹配）
        - `--period, -p`：K线周期，支持 60min/30min/15min/5min（默认 60min）
        - `--limit, -l`：获取条数（默认 100）
        **约束**：--code 和 --name 二选一必填。分钟线统一截到信号日 T 收盘。禁止追加日期参数。
        **用法示例**：
        ```bash
        ./get-intraday-candles --code 000001.SZ --period 30min
        ./get-intraday-candles --code 000001.SZ --period 15min --limit 60
        ```

        ### get-limit-list
        **用途**：查询指定股票截至信号日 T 的历史涨跌停、炸板和封板强度数据。
        **参数**：
        - `--code, -c`：股票代码（带后缀 ts_code）
        - `--name, -n`：股票名称（精确匹配）
        - `--limit-type`：涨跌停类型，U=涨停，D=跌停，Z=炸板
        - `--limit, -l`：返回条数（默认 20）
        **约束**：--code 和 --name 二选一必填。禁止追加日期参数。
        **用法示例**：
        ```bash
        ./get-limit-list --code 000001.SZ
        ./get-limit-list --code 000001.SZ --limit-type U --limit 10
        ```

        ## 分析方法（纯历史 K 线结构）
        以信号日 T 收盘价为起点，分别扫描 60min、30min、15min，必要时补 5min。
        判断标准只用历史 K 线结构：均线排列、支撑阻力位、量能、封板强度。
        对每个小周期推演价格突破、回踩、下探到支撑或跌破关键位会触发什么买点。
        卖点、止损、目标位只用于内部盈亏比推演，帮助你挑出最优入场价，不写进产物 JSON。

        ## 买点价强制覆盖（绝对约束）
        对你分析的这只股票，必须给出一个主推买点入场价。
        即便 K 线结构不理想，也要给出你判断下的最优入场价。
        不允许输出"无买点"、"暂不入场"或留空。

        ## 唯一产物：决策 JSON
        分析完成后，向 `out/decisions/{执行日}-{ts_code}.json` 写一份决策文件。
        `{执行日}` 是 T+1 开盘日，格式 `YYYY-MM-DD`；`{ts_code}` 是你分析的股票代码（含后缀）。
        宿主会在所有股票分析完成后，把同一执行日的各单票文件合并为最终的 `{执行日}.json`。
        文件结构如下，`decisions` 只含你分析的这一只票的买点：

        ```json
        {
          "formatVersion": 1,
          "executionDate": "2024-01-03",
          "decisions": [
            {
              "type": "trade-intent",
              "effectiveDate": "2024-01-03",
              "reason": "回踩 20 日均线获支撑，量能温和",
              "tsCode": "000001.SZ",
              "side": "BUY",
              "hint": "LIMIT",
              "limitPrice": 12.35
            }
          ]
        }
        ```

        字段约束：
        1. `type` 固定 `trade-intent`；`side` 固定 `BUY`；`hint` 固定 `LIMIT`。
        2. `limitPrice` 必填，是你为该股票产出的主推买点入场价（前复权价）。
        3. `effectiveDate` 与 `executionDate` 都写执行日（T+1 开盘日）。
        4. `reason` 用一句中文自然语言写买点依据。
        5. 禁止写入卖点、止损价、目标价、盈亏比、quantity、cash、position 等任何字段。
        6. 你分析的这只股票必须在 `decisions` 中出现。
        7. JSON 必须是标准 JSON，不写注释。

        ## 核心约束（HARD CONSTRAINTS）
        1. 文件系统：只能读写当前工作目录内的文件，严禁访问当前目录以外的路径（含绝对路径、`~/`、`../`）。
        2. 严禁执行任何 Python 脚本或解释器。
        3. 严禁联网与外部抓取（已在 .claude/settings.json 的 permissions.deny 中禁用）。
        4. 所有数值计算使用 `bc`，显式控制精度，禁止心算。
        5. 唯一产物是决策 JSON，不写 Markdown，不入库。

        ## 回复风格
        极度精简。被要求取数或计算时第一步直接静默调用工具，拿到结果后直接写决策 JSON 文件，不输出多余说明。
        """.trimIndent()
}
