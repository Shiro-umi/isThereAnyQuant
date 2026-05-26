package org.shiroumi.server.share

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.agent.SharePalette
import model.agent.SharePaletteRegistry

/**
 * 把分享分析记录渲染为一份独立、可匿名访问的 HTML 文档。
 *
 * 设计原则：
 * - 完全自包含：单个 HTML 字符串带齐 `<head>`/`<body>`/初始 CSS reset
 * - 视觉对齐 Compose 端：使用 Material 3 暖棕红 Dark token 作为默认主题
 *   （Compose 端虽支持多套主题切换，分享页采用项目默认主题，保持一致预期）
 * - 引入 material-web（Web Components）做高保真组件层，CDN 装载
 * - 自定义块 1:1 对应 Compose 端的 5 个 quant-* 组件
 * - K 线块只下发参数 + blockKey；真正数据由浏览器侧 share-kline.js 通过受限匿名接口拉取
 */
object ShareHtmlRenderer {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param shareToken 当前分享 token，写入页面以供 K 线接口请求时附带
     * @param contentMd  分析结果完整 Markdown 内容
     * @param tradeDate  关联交易日（来自数据库 trade_date 列），用于 `<head>` 元信息兜底
     * @param themeName  分享时刻 owner 选择的主题名（对齐 Compose AppColorTheme.name）；
     *                   未知或为 null 时退回默认的 WarmBrownRed Dark
     * @param isDark     分享时刻 owner 是否在 Dark 模式
     */
    fun render(
        shareToken: String,
        contentMd: String,
        tradeDate: String?,
        themeName: String? = null,
        isDark: Boolean = true,
    ): String {
        val segments = QuantBlockExtractor.split(contentMd)

        // 优先用报告里的 quant-header 作为 <title>
        val headerSpec = segments.asSequence()
            .filterIsInstance<MdSegment.QuantBlock>()
            .firstOrNull { it.language == "quant-header" }
            ?.let { parseJsonOrNull(it.jsonText) }
        val title = headerSpec?.stringField("title") ?: "分析报告"
        val stockName = headerSpec?.stringField("stockName")
        val pageTitle = if (stockName != null) "$stockName · $title" else title

        val palette = themeName
            ?.let { SharePaletteRegistry.find(it, isDark = isDark) }
            ?: SharePaletteRegistry.fallback
        val themeBrightness = if (isDark) "dark" else "light"

        val bodyHtml = renderBody(segments, shareToken)

        return buildString {
            append("<!DOCTYPE html>\n")
            append("<html lang=\"zh-CN\" data-theme=\"").append(themeBrightness).append("\">\n")
            append("<head>\n")
            append("  <meta charset=\"utf-8\" />\n")
            append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\" />\n")
            append("  <meta name=\"color-scheme\" content=\"").append(themeBrightness).append("\" />\n")
            append("  <meta name=\"robots\" content=\"noindex,nofollow\" />\n")
            append("  <title>").append(htmlEscape(pageTitle)).append("</title>\n")
            // 字体：与 Compose 端 Noto Sans SC 对齐
            append("  <link rel=\"preconnect\" href=\"https://fonts.googleapis.com\" />\n")
            append("  <link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin />\n")
            append("  <link href=\"https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;700&display=swap\" rel=\"stylesheet\" />\n")
            // Material Web：用 jsDelivr 提供的 importmap
            append("  <script type=\"importmap\">\n")
            append("    {\n")
            append("      \"imports\": {\n")
            append("        \"@material/web/\": \"https://esm.run/@material/web/\"\n")
            append("      }\n")
            append("    }\n")
            append("  </script>\n")
            append("  <script type=\"module\" src=\"https://esm.run/@material/web/all.js\"></script>\n")
            // 主题 CSS 变量内联输出：覆盖 share.css 中的默认值，跟随 owner 当时主题
            append("  <style>\n")
            append("    :root, [data-theme=\"").append(themeBrightness).append("\"] {\n")
            for ((token, value) in paletteCssVars(palette)) {
                append("      ").append(token).append(": ").append(value).append(";\n")
            }
            append("    }\n")
            append("  </style>\n")
            append("  <link rel=\"stylesheet\" href=\"/static/share/share.css\" />\n")
            append("</head>\n")
            append("<body>\n")
            append("  <main class=\"share-report\" data-share-token=\"").append(htmlEscape(shareToken)).append("\">\n")
            append(bodyHtml)
            append("  </main>\n")
            append("  <aside class=\"share-float-bar\">\n")
            append("    <span class=\"share-float-text\">完整分析请访问</span>\n")
            append("    <a class=\"share-float-link\" href=\"https://bigsmart.space\" target=\"_blank\" rel=\"noopener noreferrer\">bigsmart.space</a>\n")
            append("  </aside>\n")
            append("  <script type=\"module\" src=\"/static/share/share-kline.js\"></script>\n")
            append("</body>\n")
            append("</html>\n")
        }
    }

    /** SharePalette → 29 个 `--md-sys-color-*` 变量。 */
    private fun paletteCssVars(p: SharePalette): List<Pair<String, String>> = listOf(
        "--md-sys-color-primary" to hex(p.primary),
        "--md-sys-color-on-primary" to hex(p.onPrimary),
        "--md-sys-color-primary-container" to hex(p.primaryContainer),
        "--md-sys-color-on-primary-container" to hex(p.onPrimaryContainer),
        "--md-sys-color-secondary" to hex(p.secondary),
        "--md-sys-color-on-secondary" to hex(p.onSecondary),
        "--md-sys-color-secondary-container" to hex(p.secondaryContainer),
        "--md-sys-color-on-secondary-container" to hex(p.onSecondaryContainer),
        "--md-sys-color-tertiary" to hex(p.tertiary),
        "--md-sys-color-on-tertiary" to hex(p.onTertiary),
        "--md-sys-color-tertiary-container" to hex(p.tertiaryContainer),
        "--md-sys-color-on-tertiary-container" to hex(p.onTertiaryContainer),
        "--md-sys-color-error" to hex(p.error),
        "--md-sys-color-on-error" to hex(p.onError),
        "--md-sys-color-error-container" to hex(p.errorContainer),
        "--md-sys-color-on-error-container" to hex(p.onErrorContainer),
        "--md-sys-color-background" to hex(p.background),
        "--md-sys-color-on-background" to hex(p.onBackground),
        "--md-sys-color-surface" to hex(p.surface),
        "--md-sys-color-on-surface" to hex(p.onSurface),
        "--md-sys-color-surface-variant" to hex(p.surfaceVariant),
        "--md-sys-color-on-surface-variant" to hex(p.onSurfaceVariant),
        "--md-sys-color-outline" to hex(p.outline),
        "--md-sys-color-outline-variant" to hex(p.outlineVariant),
        "--md-sys-color-surface-container-lowest" to hex(p.surfaceContainerLowest),
        "--md-sys-color-surface-container-low" to hex(p.surfaceContainerLow),
        "--md-sys-color-surface-container" to hex(p.surfaceContainer),
        "--md-sys-color-surface-container-high" to hex(p.surfaceContainerHigh),
        "--md-sys-color-surface-container-highest" to hex(p.surfaceContainerHighest),
    )

    /** 0xAARRGGBB → "#RRGGBB"。alpha 暂忽略：14 套主题色板 alpha 均为 FF。 */
    private fun hex(argb: Long): String {
        val rgb = argb.toInt() and 0xFFFFFF
        return "#%06X".format(rgb)
    }

    private fun renderBody(segments: List<MdSegment>, shareToken: String): String {
        val sb = StringBuilder()
        val mdBuffer = StringBuilder()

        fun flushMarkdown() {
            if (mdBuffer.isNotEmpty()) {
                sb.append("    <section class=\"share-md\">\n")
                sb.append(MinimalMarkdownRenderer.render(mdBuffer.toString()))
                sb.append("    </section>\n")
                mdBuffer.clear()
            }
        }

        for (seg in segments) {
            when (seg) {
                is MdSegment.Text -> {
                    if (mdBuffer.isNotEmpty()) mdBuffer.append("\n\n")
                    mdBuffer.append(seg.markdown.trim())
                }
                is MdSegment.QuantBlock -> {
                    flushMarkdown()
                    val obj = parseJsonOrNull(seg.jsonText) ?: continue
                    when (seg.language) {
                        "quant-header" -> sb.append(renderHeader(obj))
                        "quant-kline" -> sb.append(renderKline(obj, shareToken))
                        "quant-limit-up" -> sb.append(renderLimitUp(obj))
                        "quant-volume-price" -> sb.append(renderVolumePrice(obj))
                        "quant-market-sentiment" -> sb.append(renderMarketSentiment(obj))
                    }
                }
            }
        }
        flushMarkdown()
        return sb.toString()
    }

    private fun renderHeader(obj: JsonObject): String {
        val title = obj.stringField("title") ?: return ""
        val stockCode = obj.stringField("stockCode") ?: return ""
        val stockName = obj.stringField("stockName")
        val analysisType = obj.stringField("analysisType") ?: ""
        val tradeDate = obj.stringField("tradeDate")
        val hasStockName = stockName != null

        return buildString {
            append("    <header class=\"qh-header\">\n")
            append("      <div class=\"qh-title-group\">\n")
            if (hasStockName) {
                append("        <h1 class=\"qh-stock-name\">").append(htmlEscape(stockName)).append("</h1>\n")
                append("        <p class=\"qh-title qh-title-sub\">").append(htmlEscape(title)).append("</p>\n")
            } else {
                append("        <h1 class=\"qh-title qh-title-main\">").append(htmlEscape(title)).append("</h1>\n")
            }
            append("      </div>\n")
            append("      <div class=\"qh-meta\">\n")
            if (analysisType.isNotBlank()) {
                append("        <span class=\"qh-meta-type\">").append(htmlEscape(analysisType)).append("</span>\n")
            }
            append("        <span class=\"qh-meta-code\">").append(htmlEscape(stockCode)).append("</span>\n")
            if (tradeDate != null) {
                append("        <span class=\"qh-meta-date\">").append(htmlEscape(tradeDate)).append("</span>\n")
            }
            append("      </div>\n")
            append("    </header>\n")
        }
    }

    private fun renderKline(obj: JsonObject, shareToken: String): String {
        val tsCode = obj.stringField("tsCode") ?: return ""
        val period = obj.stringField("period") ?: return ""
        val startDate = obj.stringField("startDate")
        val endDate = obj.stringField("endDate")
        val limitCount = obj["limit"]?.jsonPrimitive?.intOrNull
        val indicators = obj["indicators"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
        val useAdjusted = obj["useAdjusted"]?.jsonPrimitive?.booleanOrNull ?: true
        val height = (obj["height"]?.jsonPrimitive?.intOrNull ?: 360).coerceIn(240, 560)

        val blockKey = ShareTokenGenerator.blockKey(
            tsCode = tsCode,
            period = period,
            startDate = startDate,
            endDate = endDate,
            limitCount = limitCount,
            indicators = indicators?.joinToString(","),
            useAdjusted = useAdjusted,
        )

        return buildString {
            append("    <figure class=\"qk-chart\" style=\"--qk-height: ${height}px\"\n")
            append("      data-share-token=").append(attr(shareToken)).append("\n")
            append("      data-block-key=").append(attr(blockKey)).append("\n")
            append("      data-ts-code=").append(attr(tsCode)).append("\n")
            append("      data-period=").append(attr(period)).append("\n")
            if (indicators != null) append("      data-indicators=").append(attr(indicators.joinToString(","))).append("\n")
            append("    >\n")
            append("      <canvas class=\"qk-canvas\"></canvas>\n")
            append("      <figcaption class=\"qk-caption\">").append(htmlEscape(tsCode))
                .append(" · ").append(htmlEscape(period))
            if (startDate != null && endDate != null) append(" · ").append(htmlEscape(startDate)).append(" → ").append(htmlEscape(endDate))
            append("</figcaption>\n")
            append("      <div class=\"qk-placeholder\">加载中…</div>\n")
            append("    </figure>\n")
        }
    }

    private fun renderLimitUp(obj: JsonObject): String {
        val tsCode = obj.stringField("tsCode") ?: return ""
        val recentSummary = obj.stringField("recentSummary") ?: ""
        val sealQuality = obj.stringField("sealQuality")
        val consecutiveCount = obj["consecutiveCount"]?.jsonPrimitive?.intOrNull
        val institutionalAnalysis = obj.stringField("institutionalAnalysis") ?: ""
        val conclusion = obj.stringField("conclusion")

        val sealClass = when {
            sealQuality?.contains("强") == true -> "bullish"
            sealQuality?.contains("弱") == true || sealQuality?.contains("炸") == true -> "warning"
            else -> "neutral"
        }

        return buildString {
            append("    <section class=\"qb-card qb-limit-up\">\n")
            append("      <div class=\"qb-card-head\">\n")
            append("        <div class=\"qb-card-title\">\n")
            append("          <span class=\"qb-title-text\">涨停分析</span>\n")
            append("          <span class=\"qb-title-sub\">").append(htmlEscape(tsCode)).append("</span>\n")
            append("        </div>\n")
            append("        <div class=\"qb-card-badges\">\n")
            append("          <span class=\"qb-badge qb-badge-").append(sealClass).append("\">")
                .append(htmlEscape(sealQuality ?: "涨停")).append("</span>\n")
            if (consecutiveCount != null) {
                append("          <span class=\"qb-badge qb-badge-tertiary\">").append(consecutiveCount).append("连板</span>\n")
            }
            append("        </div>\n")
            append("      </div>\n")
            append("      <p class=\"qb-body\">").append(htmlEscape(naturalize(recentSummary))).append("</p>\n")
            append("      <div class=\"qb-section\">\n")
            append("        <h4 class=\"qb-section-title qb-accent-secondary\">主力行为分析</h4>\n")
            append("        <p class=\"qb-section-body\">").append(htmlEscape(naturalize(institutionalAnalysis))).append("</p>\n")
            append("      </div>\n")
            if (conclusion != null) {
                append("      <aside class=\"qb-callout qb-callout-tertiary\"><p>").append(htmlEscape(naturalize(conclusion))).append("</p></aside>\n")
            }
            append("    </section>\n")
        }
    }

    private fun renderVolumePrice(obj: JsonObject): String {
        val tsCode = obj.stringField("tsCode") ?: return ""
        val anomalyType = obj.stringField("anomalyType") ?: return ""
        val volumeRatio = obj["volumeRatio"]?.jsonPrimitive?.floatOrNull
        val priceChange = obj["priceChange"]?.jsonPrimitive?.floatOrNull
        val analysis = obj.stringField("analysis") ?: ""
        val institutional = obj.stringField("institutionalInterpretation")
        val sentimentValidation = obj.stringField("sentimentValidation")

        val anomalyClass = when {
            anomalyType.contains("上涨") -> "bullish"
            anomalyType.contains("下跌") -> "bearish"
            anomalyType.contains("天量") -> "warning"
            anomalyType.contains("地量") -> "neutral"
            else -> "secondary"
        }

        return buildString {
            append("    <section class=\"qb-card qb-volume-price\">\n")
            append("      <div class=\"qb-card-head\">\n")
            append("        <div class=\"qb-card-title\">\n")
            append("          <span class=\"qb-title-text\">量价异常</span>\n")
            append("          <span class=\"qb-title-sub\">").append(htmlEscape(tsCode)).append("</span>\n")
            append("        </div>\n")
            append("        <span class=\"qb-badge qb-badge-").append(anomalyClass).append("\">")
                .append(htmlEscape(anomalyType)).append("</span>\n")
            append("      </div>\n")
            anomalyDescription(anomalyType).takeIf { it.isNotBlank() }?.let {
                append("      <p class=\"qb-caption\">").append(htmlEscape(it)).append("</p>\n")
            }
            if (volumeRatio != null || priceChange != null) {
                append("      <div class=\"qb-chips\">\n")
                if (volumeRatio != null) {
                    append("        <span class=\"qb-chip qb-chip-")
                    append(anomalyClass).append("\"><span class=\"qb-chip-label\">量比</span>")
                    append("<span class=\"qb-chip-value\">").append(formatFloat(volumeRatio, 1)).append("x</span></span>\n")
                }
                if (priceChange != null) {
                    val cls = if (priceChange >= 0f) "bullish" else "bearish"
                    val prefix = if (priceChange >= 0f) "+" else ""
                    append("        <span class=\"qb-chip qb-chip-")
                    append(cls).append("\"><span class=\"qb-chip-label\">价格变动</span>")
                    append("<span class=\"qb-chip-value\">").append(prefix).append(formatFloat(priceChange, 1)).append("%</span></span>\n")
                }
                append("      </div>\n")
            }
            append("      <p class=\"qb-body\">").append(htmlEscape(naturalize(analysis))).append("</p>\n")
            if (institutional != null) {
                append("      <div class=\"qb-section\">\n")
                append("        <h4 class=\"qb-section-title qb-accent-secondary\">主力行为解读</h4>\n")
                append("        <p class=\"qb-section-body\">").append(htmlEscape(naturalize(institutional))).append("</p>\n")
                append("      </div>\n")
            }
            if (sentimentValidation != null) {
                append("      <aside class=\"qb-callout qb-callout-tertiary\"><p>")
                    .append(htmlEscape(naturalize(sentimentValidation))).append("</p></aside>\n")
            }
            append("    </section>\n")
        }
    }

    private fun renderMarketSentiment(obj: JsonObject): String {
        val summary = obj.stringField("summary") ?: ""
        val dialectical = obj.stringField("dialecticalRelationship") ?: ""

        return buildString {
            append("    <section class=\"qb-card qb-market-sentiment\">\n")
            append("      <h3 class=\"qb-card-title-only\">市场情绪</h3>\n")
            append("      <p class=\"qb-body\">").append(htmlEscape(naturalize(summary))).append("</p>\n")
            append("      <h4 class=\"qb-section-title qb-accent-tertiary\">市场情绪与个股情绪的辩证关系</h4>\n")
            append("      <aside class=\"qb-callout qb-callout-tertiary\"><p>")
                .append(htmlEscape(naturalize(dialectical))).append("</p></aside>\n")
            append("    </section>\n")
        }
    }

    private fun parseJsonOrNull(s: String): JsonObject? = try {
        (json.parseToJsonElement(s) as? JsonObject)
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.stringField(name: String): String? =
        this[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    /** 与 Compose 端 naturalizeReportText 对齐：把残留的内部字段名翻译成中文业务语义。 */
    private fun naturalize(text: String): String = text
        .replace("sentimentExposure", "市场参与意愿")
        .replace("bullRatio", "看涨扩散度")
        .replace("marketVol", "盘面波动水平")
        .replace("fftScore", "周期共振强弱")
        .replace("accelZ", "情绪变化速度")
        .replace("volZ", "量能异常程度")
        .replace("emptyReason", "防守原因")
        .replace("residualScore", "残余风险评分")
        .replace("limitType", "涨跌停类型")
        .replace("openTimes", "封板打开次数")
        .replace("fdAmount", "封单规模")
        .replace("limitAmount", "板上成交额")
        .replace("upStat", "连板状态")

    private fun anomalyDescription(type: String): String = when {
        type.contains("放量上涨") -> "价格上涨伴随成交量显著放大，资金主动买入意愿强"
        type.contains("缩量上涨") -> "价格上涨但成交量萎缩，上涨动能不足，需警惕见顶"
        type.contains("放量下跌") -> "价格下跌伴随成交量放大，恐慌抛售或主力出货迹象"
        type.contains("缩量下跌") -> "价格下跌但成交量萎缩，正常回调或洗盘特征"
        type.contains("天量") -> "成交量异常放大至近期极值，多空分歧剧烈"
        type.contains("地量") -> "成交量极度萎缩至近期低谷，市场交投清淡"
        else -> ""
    }

    private fun formatFloat(value: Float, scale: Int): String {
        val factor = when (scale) {
            0 -> 1.0
            1 -> 10.0
            2 -> 100.0
            else -> 1000.0
        }
        val rounded = kotlin.math.round(value.toDouble() * factor) / factor
        val str = rounded.toString()
        val dot = str.indexOf('.')
        return if (dot >= 0) {
            val decimals = str.length - dot - 1
            if (decimals >= scale) str.substring(0, dot + 1 + scale)
            else str + "0".repeat(scale - decimals)
        } else {
            str + "." + "0".repeat(scale)
        }
    }
}
