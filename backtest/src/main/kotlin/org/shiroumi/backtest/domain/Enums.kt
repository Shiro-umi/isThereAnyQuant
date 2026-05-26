package org.shiroumi.backtest.domain

import kotlinx.serialization.Serializable

/** 交易方向。 */
@Serializable
enum class Side { BUY, SELL }

/** 撮合执行提示：策略对成交时机/价格的偏好。 */
@Serializable
enum class ExecutionHint {
    /** 次开盘价（默认日线口径）。 */
    OPEN,
    /** 当日 VWAP（近似）。 */
    VWAP,
    /** 当日收盘价。 */
    CLOSE,
    /** 限价单（需配合 limitPrice）。 */
    LIMIT,
}

/**
 * 订单被阻断/调整的原因。
 *
 * 设计要点：每一种原因都对应设计文档 §2 中的某条 A 股规则或 §3 中的私有域守护。
 * 测试要求是 11 种原因各对应一个失败用例（见 TodoList M2-11）。
 */
@Serializable
enum class BlockReason {
    /** 停牌：当日不可交易。 */
    SUSPENDED,
    /** 退市/已下市。 */
    DELISTED,
    /** 新股冻结期（IPO 前 N 个交易日）。 */
    IPO_FROZEN,
    /** 涨停封死，BUY 单无法成交。 */
    LIMIT_UP_BUY,
    /** 跌停封死，SELL 单无法成交。 */
    LIMIT_DOWN_SELL,
    /** 现金不足（未走缩放路径时的硬阻断）。 */
    INSUFFICIENT_CASH,
    /** T+1 锁仓：当日买入的股不可当日卖出。 */
    INSUFFICIENT_QUANTITY_T1,
    /** 数量低于一手最低标准（且非零股清仓情形）。 */
    BELOW_LOT_SIZE,
    /** 当日流动性耗尽（撮合数量已达上限）。 */
    LIQUIDITY_EXHAUSTED,
    /** 1 买 1 卖语义：已有未平仓 Lot，禁止加仓。 */
    ALREADY_HOLDING,
    /** 1 买 1 卖语义：同一交易日同一标的禁止 BUY 与 SELL 同时出现。 */
    SAME_DAY_REVERSE,
}

/**
 * 审计原因（非阻断，仅记录"发生了什么调整"）。
 *
 * 与 [BlockReason] 的区别：[AuditReason] 描述订单被**修订**而非被**否决**；
 * 例如现金不足时按比例缩放股数，订单仍然成交但留下审计痕迹。
 */
@Serializable
enum class AuditReason {
    /** 现金不足，按比例缩放所有买单股数。 */
    CASH_SCALED,
    /** 数量按 100 股整手取整。 */
    LOT_ROUNDED,
    /** 价格按 0.01 元价格档对齐。 */
    TICK_ALIGNED,
    /** 持仓期间策略调整权重，被 1 买 1 卖语义吸收为 HOLD。 */
    WEIGHT_DRIFT_IGNORED,
    /** 公司行动（分红、送股、拆股、配股）调整持仓或现金。 */
    CORPORATE_ACTION_APPLIED,
}
