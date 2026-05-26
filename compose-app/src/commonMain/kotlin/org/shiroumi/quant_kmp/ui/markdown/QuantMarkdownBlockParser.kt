package org.shiroumi.quant_kmp.ui.markdown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object QuantMarkdownBlockParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(language: String, content: String): QuantBlock {
        if (!language.startsWith("quant-")) {
            return QuantBlock.Unknown(language, content)
        }

        val jsonContent = content.trim()
        if (jsonContent.length > QuantBlockWhitelist.MAX_JSON_BYTES) {
            return QuantBlock.Unknown(language, content)
        }

        val element = try {
            json.parseToJsonElement(jsonContent)
        } catch (_: Exception) {
            return QuantBlock.Unknown(language, content)
        }

        if (element !is JsonObject) {
            return QuantBlock.Unknown(language, content)
        }

        return when (val type = QuantBlockType.fromPrefix(language)) {
            QuantBlockType.HEADER -> parseHeader(element) ?: QuantBlock.Unknown(language, content)
            QuantBlockType.KLINE -> parseKLine(element) ?: QuantBlock.Unknown(language, content)
            QuantBlockType.LIMIT_UP -> parseLimitUp(element) ?: QuantBlock.Unknown(language, content)
            QuantBlockType.VOLUME_PRICE -> parseVolumePrice(element) ?: QuantBlock.Unknown(language, content)
            QuantBlockType.MARKET_SENTIMENT -> parseMarketSentiment(element) ?: QuantBlock.Unknown(language, content)
            null -> QuantBlock.Unknown(language, content)
        }
    }

    private fun parseHeader(obj: JsonObject): QuantBlock.Header? {
        val title = obj["title"]?.primitiveString ?: return null
        val stockCode = obj["stockCode"]?.primitiveString ?: return null
        val analysisType = obj["analysisType"]?.primitiveString ?: return null

        val stockName = obj["stockName"]?.primitiveString
        val tradeDate = obj["tradeDate"]?.primitiveString

        return QuantBlock.Header(
            ReportHeaderSpec(
                title = title,
                stockCode = stockCode,
                stockName = stockName,
                analysisType = analysisType,
                tradeDate = tradeDate
            )
        )
    }

    private fun parseKLine(obj: JsonObject): QuantBlock.KLine? {
        val tsCode = obj["tsCode"]?.primitiveString ?: return null
        val period = obj["period"]?.primitiveString ?: return null
        val startDate = obj["startDate"]?.primitiveString ?: return null
        val endDate = obj["endDate"]?.primitiveString ?: return null

        if (period !in QuantBlockWhitelist.allowedPeriods) return null

        val height = (obj["height"]?.jsonPrimitive?.intOrNull)
            ?.coerceIn(QuantBlockWhitelist.MIN_HEIGHT, QuantBlockWhitelist.MAX_HEIGHT)
            ?: QuantBlockWhitelist.DEFAULT_HEIGHT

        val indicators = obj["indicators"]?.jsonArray?.mapNotNull { el ->
            el.primitiveString?.takeIf { it in QuantBlockWhitelist.allowedIndicators }
        }?.ifEmpty { null }

        val markers = obj["markers"]?.jsonArray?.let { arr ->
            if (arr.size > QuantBlockWhitelist.MAX_MARKERS) return null
            arr.mapNotNull { el ->
                val mObj = el.jsonObjectOrNull ?: return@mapNotNull null
                val date = mObj["date"]?.primitiveString ?: return@mapNotNull null
                val type = mObj["type"]?.primitiveString ?: ""
                val label = mObj["label"]?.primitiveString ?: ""
                val price = mObj["price"]?.jsonPrimitive?.floatOrNull
                CandleMarkerSpec(date, type, label, price)
            }.ifEmpty { null }
        }

        val maxCandles = obj["maxCandles"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(QuantBlockWhitelist.MIN_VISIBLE_CANDLES, QuantBlockWhitelist.MAX_VISIBLE_CANDLES)
        val focusDate = obj["focusDate"]?.primitiveString
        val tradePlan = obj["tradePlan"]?.jsonObjectOrNull?.let { parseTradePlan(it) }

        return QuantBlock.KLine(
            KLineBlockSpec(
                tsCode = tsCode,
                period = period,
                startDate = startDate,
                endDate = endDate,
                height = height,
                indicators = indicators,
                markers = markers,
                maxCandles = maxCandles,
                focusDate = focusDate,
                tradePlan = tradePlan
            )
        )
    }

    private fun parseTradePlan(obj: JsonObject): CandleTradePlanSpec? {
        val entryPrice = obj["entryPrice"]?.jsonPrimitive?.floatOrNull ?: return null
        val stopLossPrice = obj["stopLossPrice"]?.jsonPrimitive?.floatOrNull ?: return null
        val targetPrice = obj["targetPrice"]?.jsonPrimitive?.floatOrNull ?: return null

        return CandleTradePlanSpec(
            side = obj["side"]?.primitiveString ?: "BUY",
            entryPrice = entryPrice,
            stopLossPrice = stopLossPrice,
            targetPrice = targetPrice,
            riskRewardRatio = obj["riskRewardRatio"]?.jsonPrimitive?.floatOrNull,
            entryLabel = obj["entryLabel"]?.primitiveString,
            stopLabel = obj["stopLabel"]?.primitiveString,
            targetLabel = obj["targetLabel"]?.primitiveString
        )
    }

    // ==================== LIMIT_UP ====================

    private fun parseLimitUp(obj: JsonObject): QuantBlock.LimitUp? {
        val tsCode = obj["tsCode"]?.primitiveString ?: return null
        val recentSummary = obj["recentSummary"]?.primitiveString ?: return null
        val institutionalAnalysis = obj["institutionalAnalysis"]?.primitiveString ?: return null

        val stockName = obj["stockName"]?.primitiveString
        val sealQuality = obj["sealQuality"]?.primitiveString
        val consecutiveCount = obj["consecutiveCount"]?.jsonPrimitive?.intOrNull
        val conclusion = obj["conclusion"]?.primitiveString

        return QuantBlock.LimitUp(
            LimitUpBlockSpec(
                tsCode = tsCode,
                stockName = stockName,
                recentSummary = recentSummary,
                sealQuality = sealQuality,
                consecutiveCount = consecutiveCount,
                institutionalAnalysis = institutionalAnalysis,
                conclusion = conclusion
            )
        )
    }

    // ==================== VOLUME_PRICE ====================

    private fun parseVolumePrice(obj: JsonObject): QuantBlock.VolumePrice? {
        val tsCode = obj["tsCode"]?.primitiveString ?: return null
        val anomalyType = obj["anomalyType"]?.primitiveString ?: return null
        val analysis = obj["analysis"]?.primitiveString ?: return null

        val stockName = obj["stockName"]?.primitiveString
        val volumeRatio = obj["volumeRatio"]?.jsonPrimitive?.floatOrNull
        val priceChange = obj["priceChange"]?.jsonPrimitive?.floatOrNull
        val institutionalInterpretation = obj["institutionalInterpretation"]?.primitiveString
        val sentimentValidation = obj["sentimentValidation"]?.primitiveString

        return QuantBlock.VolumePrice(
            VolumePriceBlockSpec(
                tsCode = tsCode,
                stockName = stockName,
                anomalyType = anomalyType,
                volumeRatio = volumeRatio,
                priceChange = priceChange,
                analysis = analysis,
                institutionalInterpretation = institutionalInterpretation,
                sentimentValidation = sentimentValidation
            )
        )
    }

    // ==================== MARKET_SENTIMENT ====================

    private fun parseMarketSentiment(obj: JsonObject): QuantBlock.MarketSentiment? {
        val summary = obj["summary"]?.primitiveString ?: return null
        val dialecticalRelationship = obj["dialecticalRelationship"]?.primitiveString ?: return null

        return QuantBlock.MarketSentiment(
            MarketSentimentBlockSpec(
                summary = summary,
                dialecticalRelationship = dialecticalRelationship
            )
        )
    }

    /** 安全提取 JsonElement? 的字符串值，非 JsonPrimitive 或 null 时返回 null */
    private val JsonElement?.primitiveString: String?
        get() = when (this) {
            is JsonPrimitive -> content
            else -> null
        }

    private val JsonElement.jsonObjectOrNull: JsonObject?
        get() = if (this is JsonObject) this else null
}
