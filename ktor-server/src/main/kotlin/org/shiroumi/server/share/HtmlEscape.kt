package org.shiroumi.server.share

/**
 * HTML 字符转义。匿名分享页所有动态文本最终落地前必须经过本函数，
 * 避免任何报告内容把 HTML 标签或 JS 注入到页面里。
 */
internal fun htmlEscape(text: String?): String {
    if (text.isNullOrEmpty()) return ""
    val sb = StringBuilder(text.length + 16)
    for (ch in text) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/**
 * 把字符串安全嵌入到 HTML attribute（双引号包裹）中。
 */
internal fun attr(value: String?): String = "\"" + htmlEscape(value) + "\""
