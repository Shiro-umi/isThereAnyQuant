package org.shiroumi.database.fundamental.table

import org.jetbrains.exposed.v1.core.Table

/**
 * 个股季频财务面板（stock_fundamental_quarterly）—— factor topic 线 F「个股基本面」的事实底座。
 *
 * 设计文档：private/research-docs/macro-fundamental-pressure-formula.html §6 / §7 / §9（H3）
 * 来源：fina_indicator_vip（整季全市场，period 取报告期）。
 *
 * 主键 (ts_code, end_date)：一只股一个报告期一行。
 * ⚠️ ann_date（公告日）是防未来函数的生死线：研究层取值必须用「ann_date ≤ t 的最新一期」，
 *    而非 end_date（报告期）——本表如实落两个日期，对齐纪律由研究层 Transform 负责。
 */
object StockFundamentalQuarterlyTable : Table(name = "stock_fundamental_quarterly") {
    val tsCode = varchar("ts_code", 15)
    val endDate = varchar("end_date", 8)    // 报告期 YYYYMMDD（如 20231231）
    val annDate = varchar("ann_date", 8).nullable()  // 公告日 YYYYMMDD（防未来函数关键）

    // 盈利能力
    val roe = double("roe").nullable()        // 净资产收益率
    val roeDt = double("roe_dt").nullable()   // 扣非净资产收益率
    val eps = double("eps").nullable()

    // 成长性（同比，%）
    val netprofitYoy = double("netprofit_yoy").nullable()      // 净利润同比
    val dtNetprofitYoy = double("dt_netprofit_yoy").nullable() // 扣非净利同比
    val trYoy = double("tr_yoy").nullable()                    // 营业总收入同比
    val orYoy = double("or_yoy").nullable()                    // 营业收入同比

    // 现金流质量
    val ocfToSales = double("ocf_to_sales").nullable()  // 经营现金流/营业收入
    val ocfps = double("ocfps").nullable()              // 每股经营现金流

    override val primaryKey = PrimaryKey(tsCode, endDate, name = "pk_stock_fundamental_quarterly")

    init {
        index("idx_sfq_anndate", false, annDate)
        index("idx_sfq_enddate", false, endDate)
    }
}
