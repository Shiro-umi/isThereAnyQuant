package model.agent

import kotlinx.serialization.Serializable

/**
 * Agent 分析类型枚举
 *
 * 对应 .claude/skills/ 下的各个分析 skill，用于前后端统一约定分析分类。
 */
@Serializable
enum class AgentAnalysisType(val code: String, val displayName: String) {
    ENTRY_EXIT("entry-exit", "买卖点分析"),
    RESEARCH_REPORT("research-report", "研报分析"),
    TREND("trend", "趋势分析"),
    PATTERN("pattern", "形态识别"),
    INSTITUTIONAL("institutional", "机构行为分析"),
    SUPPORT_RESISTANCE("support-resistance", "支撑阻力分析"),
    TECHNICAL_INDICATORS("technical-indicators", "技术指标分析"),
    VOLUME_PRICE("volume-price", "量价关系分析"),
    PRICE_ACTION("price-action", "价格行为分析"),
    RISK_ASSESSMENT("risk-assessment", "风险评估"),
    MARKET_SENTIMENT("market-sentiment", "市场情绪分析"),
    GENERAL("general", "综合分析");

    companion object {
        fun fromCode(code: String): AgentAnalysisType = entries.find { it.code == code } ?: GENERAL
    }
}
