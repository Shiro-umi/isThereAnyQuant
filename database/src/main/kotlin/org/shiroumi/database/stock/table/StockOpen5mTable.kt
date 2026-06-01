package org.shiroumi.database.stock.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

/**
 * 每日「首根 5 分钟 K 线」表（09:30–09:35 开盘 bar）。
 *
 * 数据源：Tushare `stk_mins` freq=5min，每股每日取 trade_time 含 `09:30:00` 的首根。
 * 用途：大阴线杀跌「当日开盘量价 → 当日收盘大阴线」日内预警研究——首根开盘 bar 是 t 日当天的
 * 新信息（次日才到达），对此前用「收盘后已知信息预测次日突发大阴线」缺失的正交维度的首次补充。
 *
 * 实测覆盖：stk_mins 5min 自 2010-01-04 起（探针 ProbeStkMins 确认，2010 前无分钟数据）。
 *
 * 字段为原始 5min OHLCV（不复权——首根研究只看当日开盘相对昨收/开盘内部结构，复权由研究层
 * 用日线昨收口径处理）。`tradeTime` 保留原始时间戳便于校验首根是否为 09:30。
 */
object StockOpen5mTable : Table(name = "stock_open_5m") {
    val tsCode = varchar("ts_code", 15).index()
    val tradeDate = date("trade_date").index()
    val tradeTime = varchar("trade_time", 25)   // 原始 trade_time（如 "2010-01-04 09:30:00"）
    val open = float("open")
    val high = float("high")
    val low = float("low")
    val close = float("close")
    val vol = float("vol")          // 成交量（stk_mins 单位：手）
    val amount = float("amount")    // 成交额（元）

    init {
        uniqueIndex("uk_open5m_code_date", tsCode, tradeDate)
        index("idx_open5m_date_code", false, tradeDate, tsCode)
    }
}
