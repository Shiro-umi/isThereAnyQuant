package org.shiroumi.server.share

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Markdown 段落分段器，按 fenced code block 切分 contentMd。
 *
 * 不复用 compose-app 的 QuantMarkdownBlockParser，因为：
 * 1. 它在 compose-app 模块，ktor-server 不依赖
 * 2. 后端只需要：抽出 quant-* 块的语言 + JSON 文本；普通段落原文透传
 *
 * 输出的 segment 序列保留原始顺序，HTML 渲染器按顺序消费。
 */
sealed class MdSegment {
    /** 普通 Markdown 段落（含可能的 ``` 非 quant-* code block） */
    data class Text(val markdown: String) : MdSegment()

    /** quant-* 自定义块；jsonText 已 trim */
    data class QuantBlock(val language: String, val jsonText: String) : MdSegment()
}

object QuantBlockExtractor {

    private val fencedRegex = Regex(
        pattern = """(?m)^```([a-zA-Z0-9_\-]+)\s*\n([\s\S]*?)\n```\s*$""",
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun split(contentMd: String): List<MdSegment> {
        val out = mutableListOf<MdSegment>()
        var cursor = 0
        for (match in fencedRegex.findAll(contentMd)) {
            val lang = match.groupValues[1]
            val body = match.groupValues[2]
            if (!lang.startsWith("quant-")) continue

            if (match.range.first > cursor) {
                val text = contentMd.substring(cursor, match.range.first)
                if (text.isNotBlank()) out += MdSegment.Text(text)
            }
            out += MdSegment.QuantBlock(lang, body.trim())
            cursor = match.range.last + 1
        }
        if (cursor < contentMd.length) {
            val tail = contentMd.substring(cursor)
            if (tail.isNotBlank()) out += MdSegment.Text(tail)
        }
        return out
    }

    /**
     * 从全部 quant-kline 块中提取参数元组，用于写入分享白名单。
     */
    fun extractKlineEntries(contentMd: String): List<KlineSpec> {
        return split(contentMd)
            .filterIsInstance<MdSegment.QuantBlock>()
            .filter { it.language == "quant-kline" }
            .mapNotNull { parseKline(it.jsonText) }
    }

    private fun parseKline(jsonText: String): KlineSpec? {
        val obj = try {
            json.parseToJsonElement(jsonText) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        val tsCode = obj["tsCode"]?.jsonPrimitive?.content ?: return null
        val period = obj["period"]?.jsonPrimitive?.content ?: return null
        val startDate = obj["startDate"]?.jsonPrimitive?.content
        val endDate = obj["endDate"]?.jsonPrimitive?.content
        val limitCount = obj["limit"]?.jsonPrimitive?.intOrNull
        val indicators = obj["indicators"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",")
        val useAdjusted = obj["useAdjusted"]?.jsonPrimitive?.booleanOrNull ?: true

        return KlineSpec(
            tsCode = tsCode,
            period = period,
            startDate = startDate,
            endDate = endDate,
            limitCount = limitCount,
            indicators = indicators,
            useAdjusted = useAdjusted,
        )
    }

    data class KlineSpec(
        val tsCode: String,
        val period: String,
        val startDate: String?,
        val endDate: String?,
        val limitCount: Int?,
        val indicators: String?,
        val useAdjusted: Boolean,
    )
}
