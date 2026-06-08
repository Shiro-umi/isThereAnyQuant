package org.shiroumi.database.stock.table

import org.jetbrains.exposed.v1.core.Table

/**
 * 龙虎榜每日汇总（Tushare top_list）—— 一行 = 一只上榜股一个交易日。
 *
 * 方向极性维度的事实底座：上榜事件本身（0/1）+ 龙虎榜净买入方向（net_amount/net_rate），
 * 暴涨前（资金抢筹上榜）vs 暴跌前（资金出逃上榜）形态不对称，是当期外生的方向信号。
 * 覆盖 2005 起（实测 2009 起每日有数据）。营业部席位明细见 top_inst。
 */
object TopListTable : Table(name = "tushare_top_list") {
    val tradeDate = varchar("trade_date", 8).index()   // YYYYMMDD（与 kpl_list 同口径）
    val tsCode = varchar("ts_code", 15).index()
    val name = varchar("name", 64).nullable()
    val close = double("close").nullable()
    val pctChange = double("pct_change").nullable()
    val turnoverRate = double("turnover_rate").nullable()
    val amount = double("amount").nullable()           // 总成交额
    val lBuy = double("l_buy").nullable()              // 龙虎榜买入额
    val lSell = double("l_sell").nullable()            // 龙虎榜卖出额
    val lAmount = double("l_amount").nullable()        // 龙虎榜成交额
    val netAmount = double("net_amount").nullable()    // 龙虎榜净买入额
    val netRate = double("net_rate").nullable()        // 净买额占比
    val amountRate = double("amount_rate").nullable()  // 龙虎榜成交额占比
    val floatValues = double("float_values").nullable()
    val reason = varchar("reason", 255).nullable()     // 上榜理由

    init {
        // top_list 每股每日一行（同股同日多上榜原因时取首条/净买入代表）。
        uniqueIndex("uk_top_list_date_code", tradeDate, tsCode)
        index("idx_top_list_code_date", false, tsCode, tradeDate)
    }
}
