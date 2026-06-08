package org.shiroumi.database.kpl.table

import org.jetbrains.exposed.v1.core.Table

/**
 * 开盘啦榜单（kpl_list）—— 题材群体维度事实底座（每日涨停个股 + 题材归属 + 连板高度）。
 *
 * 设计动机：个股下跌风险取决于所在题材集体状态（中观维度，正交于个股量价）。
 *   - theme：题材归属（如「商业航天、卫星导航」）
 *   - status：连板高度（如「首板」「3天2板」「5天4板」）→ 题材运行时间/透支度
 *   - 题材内负反馈（跌停/炸板）配合 limit_list_d；本表只含涨停股。
 *
 * 来源：Tushare kpl_list（getKplList），按交易日逐日拉。主键 (ts_code, trade_date)。
 */
object KplListTable : Table(name = "kpl_list") {
    val tsCode = varchar("ts_code", 15)
    val tradeDate = varchar("trade_date", 8)   // YYYYMMDD
    val name = varchar("name", 64).nullable()
    val theme = varchar("theme", 255).nullable()       // 题材，可能多个用顿号分隔
    val status = varchar("status", 32).nullable()      // 连板高度「N天M板」
    val tag = varchar("tag", 16).nullable()            // 涨停/跌停/炸板（默认涨停）
    val luDesc = varchar("lu_desc", 128).nullable()    // 涨停原因
    val turnoverRate = double("turnover_rate").nullable()

    override val primaryKey = PrimaryKey(tsCode, tradeDate, name = "pk_kpl_list")

    init {
        index("idx_kpl_tradedate", false, tradeDate)
    }
}
