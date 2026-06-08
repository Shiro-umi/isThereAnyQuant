package org.shiroumi.database.stock.table

import org.jetbrains.exposed.v1.core.Table

/**
 * 龙虎榜机构/营业部成交明细（Tushare top_inst）—— 一行 = 一只上榜股一个交易日一个营业部一个买卖方向。
 *
 * 席位维度的事实底座：exalter 营业部名称原文入库（不在此层分类），
 * 散户/机构分类（含「拉萨」=散户、含「机构」=机构）放 pytorch 装配层做（分类规则是研究参数）。
 * 实测 2011-2012 起每日有数据（早于此的日子缺，装配层缺失填 0 + 上榜事件位标记）。
 */
object TopInstTable : Table(name = "tushare_top_inst") {
    val tradeDate = varchar("trade_date", 8).index()   // YYYYMMDD
    val tsCode = varchar("ts_code", 15).index()
    val exalter = varchar("exalter", 128)              // 营业部名称（原文）
    val side = varchar("side", 1)                       // 0=买入前5 / 1=卖出前5
    val buy = double("buy").nullable()                 // 买入额（元）
    val buyRate = double("buy_rate").nullable()
    val sell = double("sell").nullable()               // 卖出额（元）
    val sellRate = double("sell_rate").nullable()
    val netBuy = double("net_buy").nullable()          // 净成交额（元）
    val reason = varchar("reason", 255).nullable()

    init {
        uniqueIndex("uk_top_inst_date_code_alter_side", tradeDate, tsCode, exalter, side)
        index("idx_top_inst_code_date", false, tsCode, tradeDate)
    }
}
