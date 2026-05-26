package org.shiroumi.backtest.config

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import model.PriceBasis
import org.shiroumi.backtest.domain.Money

/**
 * 一次回测运行的完整配置。
 *
 * 设计目标（详见 docs/architecture/backtest-engine-design.md §1.5）：
 * 同一份策略 + 同一行情，配置不同的 [initialCapital] 跑两遍，
 * 策略每日产出的权重序列必须完全一致——这意味着账户相关配置只影响私有域行为，
 * 不应回流到策略层。
 *
 * @param universe 股票池标签（与 strategy-server 共用："main_board_active" / "all" 等）
 */
@Serializable
data class BacktestConfig(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val initialCapital: Money,
    val universe: String = "main_board_active",
    /** 信号口径：策略侧用于因子/打分的价格基准，默认 HFQ 避免历史除权扭曲。 */
    val signalBasis: PriceBasis = PriceBasis.HFQ,
    /** 执行口径：撮合必须使用 RAW（强校验在 MatchingContext 中）。 */
    val executionBasis: PriceBasis = PriceBasis.RAW,
    val matching: MatchingPolicyConfig = MatchingPolicyConfig(),
    val slippage: SlippageConfig = SlippageConfig(),
    val costs: CostModelConfig = CostModelConfig(),
    val rules: RulesConfig = RulesConfig(),
    val output: OutputConfig = OutputConfig(),
) {
    init {
        require(startDate <= endDate) { "回测起始日必须不晚于结束日" }
        require(initialCapital.isPositive) { "初始资金必须为正" }
        require(executionBasis == PriceBasis.RAW) { "执行口径必须为 RAW" }
    }
}

@Serializable
enum class MatchingPolicyKind { OPEN_PRICE, VWAP, CLOSE_PRICE, LIMIT }

@Serializable
data class MatchingPolicyConfig(
    val policy: MatchingPolicyKind = MatchingPolicyKind.OPEN_PRICE,
)

/**
 * 滑点配置（基点制）。
 *  - BUY 实际成交价 = 参考价 × (1 + bps/10000)
 *  - SELL 实际成交价 = 参考价 × (1 - bps/10000)
 */
@Serializable
data class SlippageConfig(val basisPoints: Int = 5)

/**
 * 费用模型参数（对齐 docs/architecture/backtest-engine-design.md §2.8）。
 */
@Serializable
data class CostModelConfig(
    /** 佣金费率，默认万 2.5。 */
    val commissionRate: Double = 0.000_25,
    /** 佣金最低收，默认 5 元。 */
    val minCommission: Money = Money.ofYuan(5.0),
    /** 过户费费率，默认 0.001%（沪深统一调整后）。 */
    val transferFeeRate: Double = 0.000_01,
    /** 印花税费率，默认 0.05%，**仅卖出**。 */
    val stampDutyRate: Double = 0.000_5,
)

/**
 * 规则配置（对齐 docs/architecture/backtest-engine-design.md §2.3 / §2.4 / §2.5）。
 */
@Serializable
data class RulesConfig(
    /** 是否允许当日买入当日卖出。默认 false，严格 T+1。 */
    val allowIntradaySellOnDayBuy: Boolean = false,
    /** 排除新股前 N 个交易日。 */
    val excludeIpoDays: Int = 5,
    /** 是否排除 ST 板块。 */
    val excludeSt: Boolean = false,
    /** 主板涨跌幅。 */
    val priceLimitMainBoard: Double = 0.10,
    /** 创业板/科创板/北交所涨跌幅。 */
    val priceLimitGrowthBoard: Double = 0.20,
    /**
     * 当日流动性上限（占当日成交量比例）。null 表示不限。
     * 例如 0.05 = 单标的当日委托数量 ≤ 当日成交量 × 5%。
     */
    val liquidityFractionLimit: Double? = null,
)

/**
 * 输出配置。
 */
@Serializable
data class OutputConfig(
    /** equity 曲线 CSV 路径模板，`{run_id}` 占位会被替换。null 则不导出。 */
    val equityCurveCsv: String? = null,
)
