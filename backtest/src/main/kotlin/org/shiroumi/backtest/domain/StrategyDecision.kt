package org.shiroumi.backtest.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 策略对某个交易日的"市场观点"。
 *
 * **强制约束**：本类型及其子类型字段中**禁止出现任何账户字段**——
 * 不允许 `quantity` / `cash` / `cashAmount` / `position` / `currentPosition` /
 * `availableQty` / `availableCash` 等命名。所有账户折算工作发生在
 * [org.shiroumi.backtest.ledger]（账户私有域）内，对策略不可见。
 *
 * 字段名校验由 `StrategyDecisionFieldNamesTest`（M1-12）守护。
 */
@Serializable
sealed interface StrategyDecision {
    /** 决策生效的目标交易日（通常是 T+1）。 */
    val effectiveDate: LocalDate

    /** 决策原因摘要，便于审计与回测复盘。 */
    val reason: String

    /**
     * 目标组合：T+1 想要持有的整组权重快照。
     *
     * @param targetWeights 标的 → 权重（0.0 ~ 1.0）；累加值通常 ≤ [sentimentExposure]，
     *                       剩余仓位以现金形式持有
     * @param sentimentExposure 当期总暴露上限（由策略侧情绪推导），用于回归校验
     */
    @Serializable
    @SerialName("target-portfolio")
    data class TargetPortfolioDecision(
        override val effectiveDate: LocalDate,
        override val reason: String,
        val targetWeights: Map<String, Double>,
        val sentimentExposure: Double,
    ) : StrategyDecision

    /**
     * 显式交易意图：策略对某只标的的方向性意见。
     *
     * @param tsCode 标的代码
     * @param side BUY / SELL
     * @param weight 期望权重（0.0 ~ 1.0，可为空——表示让 OrderSizer 用默认仓位）
     * @param hint 执行价提示（开盘 / VWAP / 收盘 / 限价）
     */
    @Serializable
    @SerialName("trade-intent")
    data class TradeIntentDecision(
        override val effectiveDate: LocalDate,
        override val reason: String,
        val tsCode: String,
        val side: Side,
        val weight: Double? = null,
        val hint: ExecutionHint = ExecutionHint.OPEN,
    ) : StrategyDecision
}
