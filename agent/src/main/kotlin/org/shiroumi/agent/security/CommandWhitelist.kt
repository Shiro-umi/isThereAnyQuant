package org.shiroumi.agent.security

object CommandWhitelist {

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

    private val CLI_TOOL_PATTERNS = listOf(
        Regex("""^\./get-candles(\s+(--(code|name|limit)|-(c|n|l))\s+\S+)*\s*$"""),
        Regex("""^\./get-intraday-candles(\s+(--(code|name|period|limit)|-(c|n|p|l))\s+\S+)*\s*$"""),
        Regex("""^\./get-research-reports(\s+(--(code|name|start-date|end-date|trade-date|report-type|inst|limit|format)|-(c|n|l))\s+\S+)*\s*$"""),
        Regex("""^\./get-industry-research-reports(\s+(--(ind-name|start-date|end-date|trade-date|inst|limit|format)|-(l))\s+\S+)*\s*$"""),
        Regex("""^\./get-limit-list(\s+(--(code|name|start-date|end-date|trade-date|limit-type|limit|format)|-(c|n|l))\s+\S+)*\s*$"""),
        Regex("""^\./market-emotion\s*$"""),
    )

    private val BC_PATTERN = Regex("""^echo\s+["']([^"']*)["']\s*\|\s*bc\s*$""")
    private val BC_CONTENT_CHARS = Regex("""^[0-9.+\-*/()^;= \\a-z\n]+$""")

    fun validate(commandString: String): Result {
        val cmd = commandString.trim()
        if (cmd.isEmpty()) return Result.Denied("空命令")

        if (CLI_TOOL_PATTERNS.any { it.matches(cmd) }) {
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

        return Result.Denied("命令不在白名单中: ${cmd.take(80)}")
    }
}
