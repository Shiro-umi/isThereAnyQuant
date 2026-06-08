package org.shiroumi.database.moneyflow.table

import org.jetbrains.exposed.v1.core.Table

/**
 * 个股日频资金流向（stock_moneyflow）—— pivot-crash-stock 主力行为方向的事实底座。
 *
 * 设计文档（私有）：private/research-docs/pivot-crash-stock-formula.html（主力出货/拉升判别）
 * 来源：Tushare moneyflow（getStockMoneyFlow），单次 6000 行，按交易日逐日拉全市场。
 *
 * 业务动机：指数下跌日个股分三类——case2 高热度主力借机出货（大单净流出→跟跌）、
 * case3 高热度主力借势拉升（大单净流入→不跌）。日 K/换手无法区分二者，资金流是直接信号。
 *
 * 主键 (ts_code, trade_date)：一只股一个交易日一行。金额单位：万元（Tushare 原始口径）。
 */
object StockMoneyFlowTable : Table(name = "stock_moneyflow") {
    val tsCode = varchar("ts_code", 15)
    val tradeDate = varchar("trade_date", 8)   // YYYYMMDD

    // 大单（lg）买卖额，万元
    val buyLgAmount = double("buy_lg_amount").nullable()
    val sellLgAmount = double("sell_lg_amount").nullable()
    // 特大单（elg）买卖额，万元
    val buyElgAmount = double("buy_elg_amount").nullable()
    val sellElgAmount = double("sell_elg_amount").nullable()
    // 净流入额（万元）：主力抛压/托底的直接代理，正=净流入（吸筹/拉升），负=净流出（出货）
    val netMfAmount = double("net_mf_amount").nullable()

    override val primaryKey = PrimaryKey(tsCode, tradeDate, name = "pk_stock_moneyflow")

    init {
        index("idx_smf_tradedate", false, tradeDate)
    }
}
