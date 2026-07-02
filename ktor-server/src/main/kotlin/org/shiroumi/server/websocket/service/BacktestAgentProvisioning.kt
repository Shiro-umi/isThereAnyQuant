package org.shiroumi.server.websocket.service

import java.io.File

/**
 * 回测 agent 隔离的纯逻辑装配点（L2）。
 *
 * 把"按模式选 skill 源 / 选 CLI 产物 / 写回测 CLAUDE.md / 写禁联网 settings.json"等可独立验证的
 * 应用层隔离逻辑从 [AgentWebSocketService] 抽出，便于单测独立覆盖，同时让 service 保持薄装配。
 *
 * 边界纪律：本对象只产出内容与选择结果，不触碰会话生命周期、不依赖运行时进程。
 */
internal object BacktestAgentProvisioning {

    /** 回测 skill 源目录（相对项目根）。 */
    const val BACKTEST_SKILLS_REL: String = "private/agent-backtest-skills"

    /** 实盘 skill 源目录候选（相对项目根），按序取首个存在的目录。 */
    val LIVE_SKILLS_REL: List<String> = listOf(
        "private/agent-analysis-skills",
        "agent/analysis-skills",
    )

    /**
     * 按运行模式给出 skill 源候选目录。
     *
     *  - 回测模式：只用 `private/agent-backtest-skills`（回测衍生 skill）。
     *  - 实盘模式：`private/agent-analysis-skills` 优先，缺失回退 `agent/analysis-skills`。
     */
    fun skillSourceCandidates(projectRoot: File, backtestMode: Boolean): List<File> =
        if (backtestMode) {
            listOf(File(projectRoot, BACKTEST_SKILLS_REL))
        } else {
            LIVE_SKILLS_REL.map { File(projectRoot, it) }
        }

    /**
     * 回测模式 CLI 工具集：仅历史取数三件套，toolName 保持不变，产物指向 *-asof。
     *
     * market-emotion（无历史快照）与研报工具不进回测集。
     */
    fun backtestCliTools(): List<AgentWebSocketService.CliToolSpec> = listOf(
        AgentWebSocketService.CliToolSpec(
            toolName = "get-candles",
            installRelativePath = "tools/get-candles-asof/build/install/get-candles-asof/bin/get-candles-asof",
            gradleTask = ":tools:get-candles-asof:installDist",
        ),
        AgentWebSocketService.CliToolSpec(
            toolName = "get-intraday-candles",
            installRelativePath = "tools/get-intraday-candles-asof/build/install/get-intraday-candles-asof/bin/get-intraday-candles-asof",
            gradleTask = ":tools:get-intraday-candles-asof:installDist",
        ),
        AgentWebSocketService.CliToolSpec(
            toolName = "get-limit-list",
            installRelativePath = "tools/get-limit-list-asof/build/install/get-limit-list-asof/bin/get-limit-list-asof",
            gradleTask = ":tools:get-limit-list-asof:installDist",
        ),
    )

    /** 实盘模式 CLI 工具集：原有 6 件套裸 spec。 */
    fun liveCliTools(): List<AgentWebSocketService.CliToolSpec> = listOf(
        AgentWebSocketService.CliToolSpec(
            toolName = "get-candles",
            installRelativePath = "tools/get-candles/build/install/get-candles/bin/get-candles",
            gradleTask = ":tools:get-candles:installDist",
        ),
        AgentWebSocketService.CliToolSpec(
            toolName = "get-intraday-candles",
            installRelativePath = "tools/get-intraday-candles/build/install/get-intraday-candles/bin/get-intraday-candles",
            gradleTask = ":tools:get-intraday-candles:installDist",
        ),
        AgentWebSocketService.CliToolSpec(
            toolName = "get-research-reports",
            installRelativePath = "tools/get-research-reports/build/install/get-research-reports/bin/get-research-reports",
            gradleTask = ":tools:get-research-reports:installDist",
        ),
        AgentWebSocketService.CliToolSpec(
            toolName = "get-industry-research-reports",
            installRelativePath = "tools/get-industry-research-reports/build/install/get-industry-research-reports/bin/get-industry-research-reports",
            gradleTask = ":tools:get-industry-research-reports:installDist",
        ),
        AgentWebSocketService.CliToolSpec(
            toolName = "get-limit-list",
            installRelativePath = "tools/get-limit-list/build/install/get-limit-list/bin/get-limit-list",
            gradleTask = ":tools:get-limit-list:installDist",
        ),
        AgentWebSocketService.CliToolSpec(
            toolName = "market-emotion",
            installRelativePath = "tools/market-emotion/build/install/market-emotion/bin/market-emotion",
            gradleTask = ":tools:market-emotion:installDist",
        ),
    )

    /** 按模式给出 CLI 工具集。 */
    fun cliTools(backtestMode: Boolean): List<AgentWebSocketService.CliToolSpec> =
        if (backtestMode) backtestCliTools() else liveCliTools()

    /**
     * 回测版 CLAUDE.md 内容。
     *
     * 与实盘版差异：禁实时取数（无 market-emotion）、禁实时价、禁 Markdown 报告与渲染块、取数走 as-of、
     * 唯一产物是 out/decisions/{执行日}.json 的买点 JSON、每只票必给买点价、不入库。
     */
    fun backtestClaudeMd(skillIndexMarkdown: String): String =
        """
        # 角色设定
        你是一名量化回测信号 Agent。你在信号日 T 盘后对候选股票做纯历史 K 线结构分析，为每只股票产出一个主推买点入场价。

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
        以下工具已作为软链接存在于工作目录中，可直接调用。
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
        对你分析的每一只股票，都必须给出一个主推买点入场价。
        即便 K 线结构不理想，也要给出你判断下的最优入场价。
        不允许对任何股票输出"无买点"、"暂不入场"或留空。

        ## 唯一产物：决策 JSON
        分析完成后，向 `out/decisions/{执行日}.json` 写一份决策文件。
        `{执行日}` 是 T+1 开盘日，格式 `YYYY-MM-DD`。回测引擎在 T+1 开盘按 limitPrice 撮合。
        文件结构如下，`decisions` 中每只股票一条，只含买点：

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
        6. 每只分析的股票都必须在 `decisions` 中出现，覆盖率 100%。
        7. JSON 必须是标准 JSON，不写注释。

        ## 核心约束（HARD CONSTRAINTS）
        1. 文件系统：只能读写当前工作目录内的文件，严禁访问当前目录以外的路径（含绝对路径、`~/`、`../`）。
        2. 严禁执行任何 Python 脚本或解释器。
        3. 严禁联网与外部抓取（已在 .claude/settings.json 的 permissions.deny 中禁用）。
        4. 所有数值计算使用 `bc`，显式控制精度，禁止心算。
        5. 唯一产物是决策 JSON，不写 Markdown，不入库。

        ## 残留风险声明
        本次隔离为应用层隔离：通过 CLAUDE.md 行为约束 + 命令白名单 + permissions.deny 禁断联网 + 取数走 as-of。
        本次未实现 OS 级文件系统沙盒与进程网络层硬禁断；它们作为残留风险，由后续工作覆盖。

        ## 回复风格
        极度精简。被要求取数或计算时第一步直接静默调用工具，拿到结果后直接写决策 JSON 文件，不输出多余说明。
        """.trimIndent()

    /**
     * 终态是否落库的纯判定：回测模式一律不入库，回测产物只进 out/decisions，分析结果表只存实盘报告。
     *
     * @param backtestMode 会话是否回测模式
     * @return true 表示允许落库（仅实盘模式）
     */
    fun shouldPersistOnCompleted(backtestMode: Boolean): Boolean = !backtestMode

    /** 回测禁联网 settings.json 内容（permissions.deny）。 */
    fun backtestSettingsJson(): String =
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

    /**
     * 实盘交互 agent 的 settings.json 内容（permissions 白名单收口）。
     *
     * 两条硬约束落地：
     *  1. 禁 agent 写盘：deny 全部内置写工具（Write / Edit / MultiEdit / NotebookEdit）。deny 裸工具名把工具
     *     从 claude 上下文彻底移除。报告产出走 ACP 文本流（StateManager 累积 AgentMessageChunk → contentMd），
     *     零 Write 依赖，禁写不影响报告。
     *  2. 钉死 workDir、禁读系统信息：defaultMode=dontAsk 让未命中 allow 的工具调用在底层 claude 引擎内直接
     *     auto-deny（不弹 ask、不发 ACP 权限请求，无人审批的 ACP 场景下 ask 会挂起），agent 跑不了
     *     cat /etc/passwd、dscl、ls 等读系统命令；allow 精确放行 6 个取数 CLI（语义对齐
     *     [org.shiroumi.agent.security.CommandWhitelist] 的 LIVE 白名单）+ bc。
     *     claude 经 ACP 读写文件已被 AcpClient.resolveWorkspacePath 钉死在 workDir 内，与本白名单正交叠加。
     *
     * defaultMode 必须嵌在 permissions 对象内（与 deny/allow 平级）。claude-agent-acp 适配器读
     * settings.permissions?.defaultMode，底层 claude 引擎同样只认 permissions.defaultMode。写在顶层会被读成
     * undefined、回退 default 模式——default 下未命中 allow 触发 ACP 权限请求，被 AcpClient 的
     * autoApproveTools=true 无条件批准，收口彻底失效。
     *
     * 收口不依赖任何启动 flag：--permission-mode / --dangerously-skip-permissions / --setting-sources 走
     * claude-agent-acp 时均被适配器静默丢弃，权限模式唯一来源是本 settings.json 的 permissions.defaultMode。
     * AcpClient 已删除 --dangerously-skip-permissions，避免裸 claude CLI 回退路径被解析成 bypassPermissions 架空本收口。
     *
     * 关键：Bash 不能用裸名 deny。claude 权限语义里 deny 永远先于 allow 求值且不带例外，裸 Bash deny 会把整个
     * Bash 工具移除、让所有更具体的 Bash(./get-candles:*) allow 失效（取数全断）。故 Bash 收口靠 dontAsk 默认拒
     * + 精确 allow，不写裸 Bash deny。
     *
     * allow 保留 Read / Glob / Grep：agent 触发 skill 时按 CLAUDE.md 约定先读 SKILL.md 学报告块格式
     * （quant-kline / quant-header 等），读已被 ACP 限死 workDir，安全。区别于回测：交互保留 WebSearch / WebFetch
     * 做实时取数，故不照搬回测的禁联网 deny。
     *
     * bc 形态是 `echo "scale=4; ..." | bc`。live 会话实测：claude 引擎对管道命令逐段评估权限，echo 段命中
     * Bash(echo:*)，bc 段必须独立命中 bc 规则，缺失时整条命令被 dontAsk auto-deny。管道段的 bc 是裸命令
     * （无参数），Bash(bc:*) 是"以 bc 开头"的前缀匹配，对裸命令的命中随引擎版本有差异；故同时写精确规则
     * Bash(bc)（命中裸 bc）与前缀规则 Bash(bc:*)（命中 bc -l 等带参形态），两种匹配语义都覆盖。
     */
    fun liveSettingsJson(): String =
        """
        {
          "permissions": {
            "defaultMode": "dontAsk",
            "deny": [
              "Write",
              "Edit",
              "MultiEdit",
              "NotebookEdit"
            ],
            "allow": [
              "Read",
              "Glob",
              "Grep",
              "WebSearch",
              "WebFetch",
              "Bash(./get-candles:*)",
              "Bash(./get-intraday-candles:*)",
              "Bash(./get-research-reports:*)",
              "Bash(./get-industry-research-reports:*)",
              "Bash(./get-limit-list:*)",
              "Bash(./market-emotion:*)",
              "Bash(echo:*)",
              "Bash(bc)",
              "Bash(bc:*)"
            ]
          }
        }
        """.trimIndent()

    /**
     * 把 settings.json 内容落盘到 {workDir}/.claude/settings.json，返回写入的文件。
     *
     * 实测结论：claude-agent-acp 不解析 --disallowedTools / --permission-mode / --setting-sources 等启动参数
     * （适配器只识别 --cli / --hide-claude-auth，其余静默丢弃），工具级权限必须走 settings.json 的 permissions。
     * 适配器固定以 settingSources=[user,project,local] 读取设置：user 层为 CLAUDE_CONFIG_DIR/settings.json，
     * project 层为 {workDir}/.claude/settings.json（本方法落盘目标），local 层为 {workDir}/.claude/settings.local.json。
     */
    private fun writeSettingsJson(workDir: String, content: String): File {
        val claudeDir = File(workDir, ".claude")
        claudeDir.mkdirs()
        val settingsFile = File(claudeDir, "settings.json")
        settingsFile.writeText(content)
        return settingsFile
    }

    /**
     * 写回测禁联网 settings.json 到 {workDir}/.claude/settings.json。
     * deny 覆盖 WebFetch / WebSearch / Bash(curl) / Bash(wget) / chrome-devtools MCP 全部联网通道。
     */
    fun writeBacktestSettingsJson(workDir: String): File = writeSettingsJson(workDir, backtestSettingsJson())

    /**
     * 写实盘交互 settings.json 到 {workDir}/.claude/settings.json。
     * permissions.defaultMode=dontAsk 做 deny-by-default，deny 全部内置写工具，
     * allow 精确放行 6 取数 CLI + bc + Read/Glob/Grep + WebSearch/WebFetch。
     */
    fun writeLiveSettingsJson(workDir: String): File = writeSettingsJson(workDir, liveSettingsJson())

    /** 空 local 设置：零 allow、零 deny，让 local 层不放行任何命令。 */
    private fun emptyLocalSettingsJson(): String =
        """
        {
          "permissions": {
            "allow": [],
            "deny": []
          }
        }
        """.trimIndent()

    /**
     * 写空 settings.local.json 到 {workDir}/.claude/settings.local.json。
     *
     * 收口 local 层：claude CLI 自身把用户历史"always allow"命令持久化进 settings.local.json，底层引擎按
     * settingSources=[user, project, local] 合并 allow/deny，local 命中即放行，会架空 project 层 settings.json
     * 的 defaultMode=dontAsk（agent 曾靠 local 里的 Bash(dscl . list /Users) 列出系统用户）。provisioning 每次
     * 会话把 local 覆盖为空 allow，与 writeLiveSettingsJson 成对落盘，堵死这条旁路。
     *
     * 覆盖而非删除：settings.js 对 local 目录挂 fs.watch，删除给 CLI 重建带 allow 文件的机会；覆盖为显式空
     * allow 是幂等占位，CLI 读到确定空集。
     *
     * 安全性绑定 dontAsk 常驻：dontAsk 下未命中 allow 的工具在引擎内直接 auto-deny、不弹审批、不回写 local，
     * 故写空后长期保持干净。若把实盘 defaultMode 改回 default（未命中触发 ACP 权限请求，被 AcpClient 的
     * autoApproveTools=true 无条件批准），被批准的调用会以 destination=localSettings 回写 local，空覆盖拦不住。
     * 此约束与 dontAsk 绑定，非本方法引入。
     */
    fun writeLiveLocalSettingsJson(workDir: String): File {
        val claudeDir = File(workDir, ".claude")
        claudeDir.mkdirs()
        return File(claudeDir, "settings.local.json").also { it.writeText(emptyLocalSettingsJson()) }
    }
}
