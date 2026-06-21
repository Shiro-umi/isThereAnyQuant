package org.shiroumi.agent.security

/**
 * Agent 终端命令白名单。
 *
 * 实盘与回测两套白名单按 [Mode] 切换：
 *  - [Mode.LIVE]：原有实盘工具集（6 个 CLI + bc），允许各工具自身的日期参数。
 *  - [Mode.BACKTEST]：仅放行 as-of 历史取数（get-candles / get-intraday-candles / get-limit-list）+ bc。
 *    回测口径下取数日期由宿主 wrapper 写死（注入 --as-of），白名单不强制 agent 写 --as-of，
 *    反而禁止 agent 追加任何日期类参数（--as-of / --start-date / --end-date / --trade-date），
 *    确保信号日 T 的 as-of 锁定不被 agent 绕过。不放行 market-emotion 与研报工具。
 *
 * 工具产物本身按方案 A 指向 *-asof 二进制，但 toolName 保持 get-candles 等不变，
 * 因此白名单仍按 `./get-candles` 之类的名字匹配，无需感知底层是否 asof。
 *
 * 注意(本机实测固化):claude SDK 在内部自行 spawn Bash 执行工具命令,ACP client 的 terminal 通道
 * (`AcpClient.QuantClientSessionOperations.terminalCreate`)从未被 @zed-industries/claude-agent-acp
 * 调用 → 本白名单当前不在任何执行路径上拦命令,属语义文档 + 纵深保留(规则被 CommandWhitelistBacktestTest 锁定)。
 * 回测回填 agent 的真正 OS 层关押由 [org.shiroumi.agent.acp.SandboxProfile] 承担:process-exec 白名单
 * 拦 projectRoot 根下脚本(start-release.sh/deploy.sh/gradlew),无 network-inbound 让 JVM 绑不了监听端口。
 */
object CommandWhitelist {

    /** 运行模式：决定使用哪套命令白名单。 */
    enum class Mode { LIVE, BACKTEST }

    sealed class Result {
        data object Allowed : Result()
        data class Denied(val reason: String) : Result()
    }

    private val DANGEROUS_PATTERNS = listOf(
        Regex(""";"""),
        Regex("""&&"""),
        Regex("""\|\|"""),
        Regex("""`"""),
        Regex("""\$\("""),
        Regex("""\$\{"""),
        Regex(""">"""),
    )

    // 实盘白名单：6 个 CLI 工具，允许各自完整参数集。
    private val LIVE_CLI_TOOL_PATTERNS = listOf(
        Regex("""^\./get-candles(\s+(--(code|name|limit)|-(c|n|l))\s+\S+)*\s*$"""),
        Regex("""^\./get-intraday-candles(\s+(--(code|name|period|limit)|-(c|n|p|l))\s+\S+)*\s*$"""),
        Regex("""^\./get-research-reports(\s+(--(code|name|start-date|end-date|trade-date|report-type|inst|limit|format)|-(c|n|l))\s+\S+)*\s*$"""),
        Regex("""^\./get-industry-research-reports(\s+(--(ind-name|start-date|end-date|trade-date|inst|limit|format)|-(l))\s+\S+)*\s*$"""),
        Regex("""^\./get-limit-list(\s+(--(code|name|start-date|end-date|trade-date|limit-type|limit|format)|-(c|n|l))\s+\S+)*\s*$"""),
        Regex("""^\./market-emotion\s*$"""),
    )

    // 回测白名单：仅 3 个历史取数工具，禁止任何日期类参数（--as-of / --start-date / --end-date / --trade-date）。
    // 日期由宿主 wrapper 写死，agent 不得追加。不含 market-emotion 与研报工具。
    private val BACKTEST_CLI_TOOL_PATTERNS = listOf(
        Regex("""^\./get-candles(\s+(--(code|name|limit)|-(c|n|l))\s+\S+)*\s*$"""),
        Regex("""^\./get-intraday-candles(\s+(--(code|name|period|limit)|-(c|n|p|l))\s+\S+)*\s*$"""),
        Regex("""^\./get-limit-list(\s+(--(code|name|limit-type|limit)|-(c|n|l))\s+\S+)*\s*$"""),
    )

    private val BC_PATTERN = Regex("""^echo\s+["']([^"']*)["']\s*\|\s*bc\s*$""")
    private val BC_CONTENT_CHARS = Regex("""^[0-9.+\-*/()^;= \\a-z\n]+$""")

    /**
     * 校验命令是否在白名单内。
     *
     * @param mode 运行模式，默认 [Mode.LIVE] 维持实盘行为，老调用方零改动。
     */
    fun validate(commandString: String, mode: Mode = Mode.LIVE): Result {
        val cmd = commandString.trim()
        if (cmd.isEmpty()) return Result.Denied("空命令")

        val cliPatterns = when (mode) {
            Mode.LIVE -> LIVE_CLI_TOOL_PATTERNS
            Mode.BACKTEST -> BACKTEST_CLI_TOOL_PATTERNS
        }
        if (cliPatterns.any { it.matches(cmd) }) {
            return Result.Allowed
        }

        val bcMatch = BC_PATTERN.matchEntire(cmd)
        if (bcMatch != null) {
            val expr = bcMatch.groupValues[1]
            return if (BC_CONTENT_CHARS.matches(expr)) {
                Result.Allowed
            } else {
                Result.Denied("bc 表达式包含非法字符: ${expr.take(50)}")
            }
        }

        for (pattern in DANGEROUS_PATTERNS) {
            if (pattern.containsMatchIn(cmd)) {
                return Result.Denied("包含危险的 shell 元字符")
            }
        }

        return Result.Denied("命令不在白名单中($mode): ${cmd.take(80)}")
    }
}
