package org.shiroumi.quant_kmp.ui.markdown

/**
 * Agent Markdown 自定义组件声明模型
 *
 * 对应技术方案中的"自定义块协议"：
 * <code>```quant-{component}
 * { JSON }
 * ```</code>
 */

/** 报告头部声明参数（杂志封面式） */
data class ReportHeaderSpec(
    val title: String,
    val stockCode: String,
    val stockName: String? = null,
    val analysisType: String,
    val tradeDate: String? = null
)

/** K 线图声明参数 */
data class KLineBlockSpec(
    val tsCode: String,
    val period: String,
    val startDate: String,
    val endDate: String,
    val height: Int = 360,
    val indicators: List<String>? = null,
    val markers: List<CandleMarkerSpec>? = null,
    val maxCandles: Int? = null,
    val focusDate: String? = null,
    val tradePlan: CandleTradePlanSpec? = null
)

/** K 线标记点声明 */
data class CandleMarkerSpec(
    val date: String,
    val type: String,
    val label: String,
    val price: Float? = null
)

/** 买卖计划与盈亏比区间声明 */
data class CandleTradePlanSpec(
    val side: String = "BUY",
    val entryPrice: Float,
    val stopLossPrice: Float,
    val targetPrice: Float,
    val riskRewardRatio: Float? = null,
    val entryLabel: String? = null,
    val stopLabel: String? = null,
    val targetLabel: String? = null
)

/** 涨停/封板分析声明参数 */
data class LimitUpBlockSpec(
    val tsCode: String,
    val stockName: String? = null,
    /** 近 N 日涨停记录摘要 */
    val recentSummary: String,
    /** 封板质量：强封、弱封、炸板 */
    val sealQuality: String? = null,
    /** 连板数 */
    val consecutiveCount: Int? = null,
    /** 主力行为分析文本（自然语言） */
    val institutionalAnalysis: String,
    /** 综合结论 */
    val conclusion: String? = null
)

/** 异常量价关系声明参数 */
data class VolumePriceBlockSpec(
    val tsCode: String,
    val stockName: String? = null,
    /** 异常类型：放量上涨、缩量上涨、放量下跌、缩量下跌、天量、地量 */
    val anomalyType: String,
    /** 量比（当前量/均量） */
    val volumeRatio: Float? = null,
    /** 价格变动幅度（%） */
    val priceChange: Float? = null,
    /** 量价关系分析文本 */
    val analysis: String,
    /** 主力行为解读 */
    val institutionalInterpretation: String? = null,
    /** 情绪交叉验证 */
    val sentimentValidation: String? = null
)

/** 市场情绪块声明参数 */
data class MarketSentimentBlockSpec(
    /** 市场情绪总览（自然语言） */
    val summary: String,
    /** 市场情绪与个股情绪的辩证关系（特殊渲染） */
    val dialecticalRelationship: String
)

/** 可识别的自定义块类型 */
enum class QuantBlockType(val prefix: String) {
    HEADER("quant-header"),
    KLINE("quant-kline"),
    LIMIT_UP("quant-limit-up"),
    VOLUME_PRICE("quant-volume-price"),
    MARKET_SENTIMENT("quant-market-sentiment");

    companion object {
        /** 根据 language 前缀查找匹配的块类型 */
        fun fromPrefix(language: String): QuantBlockType? =
            entries.firstOrNull { language == it.prefix }
    }
}

/** 解析结果：成功时包含业务数据，失败或未识别时记录原始信息 */
sealed class QuantBlock {
    data class Header(val spec: ReportHeaderSpec) : QuantBlock()
    data class KLine(val spec: KLineBlockSpec) : QuantBlock()
    data class LimitUp(val spec: LimitUpBlockSpec) : QuantBlock()
    data class VolumePrice(val spec: VolumePriceBlockSpec) : QuantBlock()
    data class MarketSentiment(val spec: MarketSentimentBlockSpec) : QuantBlock()
    data class Unknown(val language: String, val content: String) : QuantBlock()
}

// ==================== 白名单常量 ====================

object QuantBlockWhitelist {

    val allowedPeriods = setOf(
        "DAY", "WEEK", "MONTH",
        "MIN_60", "MIN_30", "MIN_15", "MIN_5"
    )

    val allowedIndicators = setOf(
        "MA20", "EMA20", "VOLUME", "RSI", "MACD", "BOLL"
    )

    const val MAX_KLINE_PER_REPORT = 3
    const val MAX_MARKERS = 20
    const val MAX_JSON_BYTES = 8192
    const val DEFAULT_HEIGHT = 360
    const val MIN_HEIGHT = 240
    const val MAX_HEIGHT = 560
    const val MIN_VISIBLE_CANDLES = 10
    const val MAX_VISIBLE_CANDLES = 60
}
