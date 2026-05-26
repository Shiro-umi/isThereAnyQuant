package org.shiroumi.backtest.output

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.shiroumi.backtest.domain.AuditReason
import org.shiroumi.backtest.domain.BlockReason
import org.shiroumi.backtest.domain.DraftOrder
import org.shiroumi.backtest.domain.Fill
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StockPosition

/**
 * 一笔订单的完整轨迹：草稿 → 校验 →（撮合 | 阻断）。
 *
 * 设计意图：把订单生命周期"折叠"成一条审计可追溯的记录，便于人工复盘。
 */
@Serializable
data class OrderRecord(
    val draft: DraftOrder,
    val fill: Fill? = null,
    val blockedReason: BlockReason? = null,
    val blockedDetail: String? = null,
)

/**
 * 某交易日收盘后的持仓快照。
 *
 *  - [equity] 是 mark-to-market 总权益（现金 + Σ qty × 收盘价）
 *  - [positions] 已结算的持仓集合（包含 settled 与未 settled 两种状态）
 */
@Serializable
data class DailyPositionSnapshot(
    val tradeDate: LocalDate,
    val cash: Money,
    val equity: Money,
    val positions: List<StockPosition>,
)

/**
 * 现金流：每笔成交、每笔分红/印花税等都会落一条。
 *
 *  - [amount] 正数表示流入（卖出回款、分红），负数表示流出（买入、费用）
 */
@Serializable
data class CashFlow(
    val tradeDate: LocalDate,
    val amount: Money,
    val tag: CashFlowTag,
    val note: String? = null,
)

@Serializable
enum class CashFlowTag {
    BUY,
    SELL,
    COMMISSION,
    TRANSFER_FEE,
    STAMP_DUTY,
    DIVIDEND,
    OTHER,
}

/** 权益曲线上的一个采样点。 */
@Serializable
data class EquityPoint(
    val tradeDate: LocalDate,
    val cash: Money,
    val positionValue: Money,
    val equity: Money,
)

/**
 * 审计明细：被阻断、被缩放、被吸收的"非正常"路径全部落到这里。
 *
 *  - [reason] / [auditReason] 二选一非空：
 *      - 撮合被阻断时填 [reason]（[BlockReason]）
 *      - 订单被调整时填 [auditReason]（[AuditReason]）
 */
@Serializable
data class TradeAudit(
    val tradeDate: LocalDate,
    val tsCode: String,
    val side: Side?,
    val reason: BlockReason? = null,
    val auditReason: AuditReason? = null,
    val detail: String,
    /** 现金缩放比例，仅 CASH_SCALED 场景填充。 */
    val scaleRatio: Double? = null,
)

/**
 * 整段回测的绩效汇总。
 */
@Serializable
data class PerformanceMetrics(
    val totalReturn: Double,
    val annualizedReturn: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val sortino: Double,
    val winRate: Double,
    val turnover: Double,
    val avgHoldingDays: Double,
)

/**
 * 一次回测运行的最终产物。
 *
 * **设计要点**：所有字段都不引用任何 strategy-server:core 内部类型，
 * 输出可以安全地序列化为 JSON、写入数据库、回流给 CLI/前端，
 * 但**永远不会**回流给策略层。
 */
@Serializable
data class SimulationResult(
    val runId: String,
    val orders: List<OrderRecord>,
    val positions: List<DailyPositionSnapshot>,
    val cashFlows: List<CashFlow>,
    val equityCurve: List<EquityPoint>,
    val audits: List<TradeAudit>,
    /** 每一段「建仓 → 清仓」生命周期的贡献明细；用于单票贡献、胜率分布等可视化。 */
    val lotContributions: List<LotContribution>,
    val metrics: PerformanceMetrics,
)
