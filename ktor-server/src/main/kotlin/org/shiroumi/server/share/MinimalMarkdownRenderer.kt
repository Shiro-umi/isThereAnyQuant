package org.shiroumi.server.share

/**
 * 最小 Markdown 子集渲染器，仅服务于分享页正文中夹杂的标准 Markdown 段落。
 *
 * 支持范围（足够覆盖 Agent 报告里的真实语法）：
 * - ATX 标题 # ## ### #### ##### ######
 * - 段落（空行分隔）
 * - 无序列表 - / *
 * - 有序列表 1. 2.
 * - 引用 >
 * - 水平线 ---
 * - 行内：**粗** / *斜* / `代码` / [text](url)
 *
 * 故意不支持：
 * - 表格（Agent 报告不用）
 * - HTML 透传（安全考虑，全部转义）
 * - 嵌套列表（保持简单）
 *
 * 所有输出都已 escape，外部直接拼接到 HTML 即可。
 */
object MinimalMarkdownRenderer {

    fun render(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val out = StringBuilder()
        var i = 0
        var inUl = false
        var inOl = false
        var paragraphBuf: MutableList<String>? = null

        fun closeLists() {
            if (inUl) {
                out.append("</ul>\n")
                inUl = false
            }
            if (inOl) {
                out.append("</ol>\n")
                inOl = false
            }
        }

        fun flushParagraph() {
            paragraphBuf?.let { buf ->
                if (buf.isNotEmpty()) {
                    out.append("<p>").append(renderInline(buf.joinToString(" "))).append("</p>\n")
                }
            }
            paragraphBuf = null
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()

            // GFM 表格：当前行是表头（| a | b |），下一行是分隔（| --- | --- |），后续连续 | 行是数据
            if (i + 1 < lines.size && isTableHeader(line) && isTableSeparator(lines[i + 1].trim())) {
                flushParagraph(); closeLists()
                val headerCells = splitTableRow(line)
                val aligns = parseAlignments(lines[i + 1].trim())
                val bodyRows = mutableListOf<List<String>>()
                var j = i + 2
                while (j < lines.size) {
                    val candidate = lines[j].trim()
                    if (candidate.isEmpty() || !candidate.startsWith("|")) break
                    bodyRows += splitTableRow(candidate)
                    j++
                }
                renderTable(out, headerCells, bodyRows, aligns)
                i = j
                continue
            }

            when {
                line.isEmpty() -> {
                    flushParagraph()
                    closeLists()
                }

                line.startsWith("###### ") -> {
                    flushParagraph(); closeLists()
                    out.append("<h6>").append(renderInline(line.substring(7))).append("</h6>\n")
                }
                line.startsWith("##### ") -> {
                    flushParagraph(); closeLists()
                    out.append("<h5>").append(renderInline(line.substring(6))).append("</h5>\n")
                }
                line.startsWith("#### ") -> {
                    flushParagraph(); closeLists()
                    out.append("<h4>").append(renderInline(line.substring(5))).append("</h4>\n")
                }
                line.startsWith("### ") -> {
                    flushParagraph(); closeLists()
                    out.append("<h3>").append(renderInline(line.substring(4))).append("</h3>\n")
                }
                line.startsWith("## ") -> {
                    flushParagraph(); closeLists()
                    out.append("<h2>").append(renderInline(line.substring(3))).append("</h2>\n")
                }
                line.startsWith("# ") -> {
                    flushParagraph(); closeLists()
                    out.append("<h1>").append(renderInline(line.substring(2))).append("</h1>\n")
                }

                line == "---" || line == "***" || line == "___" -> {
                    flushParagraph(); closeLists()
                    out.append("<hr/>\n")
                }

                line.startsWith("> ") -> {
                    flushParagraph(); closeLists()
                    out.append("<blockquote>").append(renderInline(line.substring(2))).append("</blockquote>\n")
                }

                line.startsWith("- ") || line.startsWith("* ") -> {
                    flushParagraph()
                    if (inOl) { out.append("</ol>\n"); inOl = false }
                    if (!inUl) { out.append("<ul>\n"); inUl = true }
                    out.append("  <li>").append(renderInline(line.substring(2))).append("</li>\n")
                }

                Regex("""^\d+\.\s+""").containsMatchIn(line) -> {
                    flushParagraph()
                    if (inUl) { out.append("</ul>\n"); inUl = false }
                    if (!inOl) { out.append("<ol>\n"); inOl = true }
                    val content = line.replaceFirst(Regex("""^\d+\.\s+"""), "")
                    out.append("  <li>").append(renderInline(content)).append("</li>\n")
                }

                else -> {
                    closeLists()
                    val buf = paragraphBuf ?: mutableListOf<String>().also { paragraphBuf = it }
                    buf.add(line)
                }
            }
            i++
        }
        flushParagraph()
        closeLists()
        return out.toString()
    }

    // ===== GFM 表格 =====

    private fun isTableHeader(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) return false
        // 至少有 3 个 |：开头 + 中间 + 结尾，确保有至少 1 个数据列
        return trimmed.count { it == '|' } >= 3
    }

    private fun isTableSeparator(line: String): Boolean {
        if (!line.startsWith("|") || !line.endsWith("|")) return false
        val cells = splitTableRow(line)
        if (cells.isEmpty()) return false
        return cells.all { cell ->
            val c = cell.trim()
            c.isNotEmpty() && c.all { it == '-' || it == ':' } && c.contains('-')
        }
    }

    /** 把 "| a | b | c |" 拆成 ["a", "b", "c"]，trim 每段。 */
    private fun splitTableRow(line: String): List<String> {
        val body = line.trim().removePrefix("|").removeSuffix("|")
        return body.split("|").map { it.trim() }
    }

    /** 分隔行支持对齐：`:--` 左、`--:` 右、`:-:` 居中、`---` 默认。 */
    private enum class Align { LEFT, RIGHT, CENTER, DEFAULT }

    private fun parseAlignments(sepLine: String): List<Align> {
        return splitTableRow(sepLine).map { cell ->
            val c = cell.trim()
            val left = c.startsWith(":")
            val right = c.endsWith(":")
            when {
                left && right -> Align.CENTER
                right -> Align.RIGHT
                left -> Align.LEFT
                else -> Align.DEFAULT
            }
        }
    }

    private fun renderTable(
        out: StringBuilder,
        header: List<String>,
        rows: List<List<String>>,
        aligns: List<Align>,
    ) {
        out.append("<div class=\"share-table-wrap\">\n")
        out.append("  <table class=\"share-table\">\n")
        out.append("    <thead><tr>")
        header.forEachIndexed { idx, cell ->
            out.append("<th").append(alignAttr(aligns.getOrNull(idx))).append(">")
                .append(renderInline(cell)).append("</th>")
        }
        out.append("</tr></thead>\n")
        if (rows.isNotEmpty()) {
            out.append("    <tbody>\n")
            for (row in rows) {
                out.append("      <tr>")
                for (idx in header.indices) {
                    val cell = row.getOrNull(idx).orEmpty()
                    out.append("<td").append(alignAttr(aligns.getOrNull(idx))).append(">")
                        .append(renderInline(cell)).append("</td>")
                }
                out.append("</tr>\n")
            }
            out.append("    </tbody>\n")
        }
        out.append("  </table>\n</div>\n")
    }

    private fun alignAttr(align: Align?): String = when (align) {
        Align.LEFT -> " style=\"text-align:left\""
        Align.RIGHT -> " style=\"text-align:right\""
        Align.CENTER -> " style=\"text-align:center\""
        else -> ""
    }

    /**
     * 行内 Markdown：**粗** / *斜* / `代码` / [text](url)。
     * 实现策略：先全文 escape，再用占位符替换法替换 inline 语法，避免顺序问题。
     */
    private fun renderInline(raw: String): String {
        var s = htmlEscape(raw)

        // 行内代码（优先级最高，里面不再解析其他语法）
        s = Regex("""`([^`]+)`""").replace(s) { m ->
            "<code>${m.groupValues[1]}</code>"
        }

        // 链接 [text](url) — url 已经过 escape（含 & 等），但要再防 javascript: 协议
        s = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""").replace(s) { m ->
            val text = m.groupValues[1]
            val url = m.groupValues[2]
            val safeUrl = if (
                url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("/") ||
                url.startsWith("#")
            ) url else "#"
            """<a href="$safeUrl" target="_blank" rel="noopener noreferrer">$text</a>"""
        }

        // 加粗 **text**
        s = Regex("""\*\*([^*]+)\*\*""").replace(s) { "<strong>${it.groupValues[1]}</strong>" }

        // 斜体 *text*
        s = Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""").replace(s) { "<em>${it.groupValues[1]}</em>" }

        return s
    }
}
