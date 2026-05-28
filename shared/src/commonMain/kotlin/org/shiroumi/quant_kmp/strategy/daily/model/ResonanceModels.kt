package org.shiroumi.quant_kmp.strategy.daily.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 单日因子快照 —— 38 个市场情绪因子在 T 日的取值。
 *
 * 这是研究模块和生产模块之间唯一的数据交换契约。
 * 研究侧从 sentiment_factor_daily 表读取，生产侧从内存缓存读取。
 * 结构完全一致，确保两个环境对同一交易日产生相同的因子视图。
 */
@Serializable
data class SentimentFactorSnapshot(
    val tradeDate: LocalDate,
    /** 38 个因子名 → 取值，允许 null（缺失） */
    val factors: Map<String, Double?>,
    val y1Raw: Double? = null,
    val y2Raw: Double? = null,
    val y3Raw: Double? = null,
    val yComposite: Double? = null,
    val notes: String? = null,
)

/**
 * 市场状态分类 —— StateClassifier 的输出。
 *
 * 三个轴各 3 档，共 27 种组合。同时给出业务语义分类（regimeCategory）
 * 方便 SelectionRuleEngine 按六大状态查询。
 */
@Serializable
data class MarketRegime(
    val tradeDate: LocalDate,
    /** 趋势方向：LOW / MID / HIGH */
    val trendLevel: TrendLevel,
    /** 分化度：LOW / MID / HIGH */
    val dispersionLevel: TrendLevel,
    /** 量能水平：LOW / MID / HIGH */
    val volumeLevel: TrendLevel,
    /** 业务语义分类 */
    val regimeCategory: RegimeCategory,
    /** 用于匹配的条件状态 id，格式与 ResonanceCard.state_id 对齐 */
    val stateId: String,
)

@Serializable
enum class TrendLevel { LOW, MID, HIGH }

@Serializable
enum class RegimeCategory {
    /** 恐慌释放：下跌 + 高分化 + 放量 */
    PANIC_RELEASE,
    /** 强势震荡：上涨 + 高分化 + 放量 */
    STRONG_OSCILLATION,
    /** 趋势延续：上涨 + 低分化 + 缩量 */
    TREND_CONTINUATION,
    /** 底部守望：下跌 + 低分化 + 缩量 */
    BOTTOM_WATCH,
    /** 极端博弈：震荡 + 高分化 + 放量 */
    EXTREME_GAME,
    /** 跨状态基线：全状态通用 */
    CROSS_REGIME,
}

/**
 * 个股筛选条件 —— SelectionRule 的组成单元。
 *
 * 例: StockFilter("marketCap", "between", "20,200") 表示筛选流通市值 20-200 亿
 */
@Serializable
data class StockFilter(
    /** 筛选维度: turnoverAmount / pctChg / marketCap / volumeRatio / ... */
    val dimension: String,
    /** 运算符: ">=" / "<=" / "between" / "rank_top_pct" */
    val operator: String,
    /** 阈值: "10" / "0.03" / "20,200" */
    val threshold: String,
    /** 业务说明 */
    val description: String = "",
)

/**
 * 选股规则 —— SelectionRuleEngine 的输出。
 *
 * 每条规则来自 qualified 共振卡片中一个或多个 (factor → Y) 映射，
 * 翻译为可执行的股票筛选条件 + 持仓参数。
 */
@Serializable
data class SelectionRule(
    /** 规则名称 */
    val name: String,
    /** 适用市场状态 */
    val regimeStateId: String,
    /** 支撑此规则的 A 类共振卡片数 */
    val evidenceCardCount: Int,
    /** 对应的目标 Y 分量（Y1/Y2/Y3） */
    val targetY: String,
    /** 建议持仓天数（1/3/5） */
    val horizon: Int,
    /** 建议仓位占比 (0-1) */
    val positionRatio: Double,
    /** 核心筛选条件 */
    val filters: List<StockFilter>,
    /** 出场条件 */
    val exitCondition: String,
    /** 主导频带（F1b/F2a） */
    val dominantBand: String,
    /** 该规则可选的股票数预估（基于当前市场环境的保守估计） */
    val estimatedPoolSize: Int,
)

/**
 * Qualified 共振卡片的最小化表示 —— SelectionRuleEngine 的输入。
 *
 * 与 research/output/ResonanceCard 共享相同的 JSON schema 字段名（snake_case），
 * 可直接从研究产出的 JSON 文件反序列化。
 * 只保留 SelectionRuleEngine 需要的字段，减少 shared 模块的耦合面。
 */
@Serializable
data class QualifiedResonance(
    @SerialName("factor_i")        val factorI: String,
    @SerialName("factor_name")    val factorName: String,
    @SerialName("factor_type")    val factorType: String = "single",
    @SerialName("factor_j")       val factorJ: String? = null,
    @SerialName("target_y")       val targetY: String,
    val horizon: Int,
    val band: String,
    @SerialName("state_id")       val stateId: String,
    @SerialName("mean_coherence") val meanCoherence: Double? = null,
    @SerialName("oos_ic")         val oosIc: Double? = null,
    @SerialName("hit_rate")       val hitRate: Double? = null,
    @SerialName("lead_days_lag")  val leadDaysLag: Double? = null,
    @SerialName("qualified")      val qualified: Boolean = false,
    @SerialName("conclusion_level") val conclusionLevel: String? = null,
)
