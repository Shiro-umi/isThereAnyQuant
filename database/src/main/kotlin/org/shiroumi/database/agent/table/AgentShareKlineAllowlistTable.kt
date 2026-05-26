package org.shiroumi.database.agent.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * 分享分析结果中允许匿名访问的 K 线请求白名单（common_db）
 *
 * 表名: agent_share_kline_allowlist
 *
 * 设计目的：
 * 匿名 K 线接口 /api/v1/public/share/{token}/candles 必须严格绑定到分享时刻
 * 报告中实际出现的 K 线参数组合，禁止匿名用户用合法 shareToken 去查询任意股票/任意区间。
 *
 * 一个 shareToken 对应一条分析报告，报告里可能含 0-3 个 quant-kline 块；
 * 每个块的参数 (tsCode, period, startDate, endDate, indicators) 在生成分享链接时
 * 一次性提取并写入本表，用 blockKey 做请求侧定位。
 */
object AgentShareKlineAllowlistTable : UUIDTable("agent_share_kline_allowlist") {

    /** 关联的分享 token */
    val shareToken = varchar("share_token", 32).index()

    /** 块定位 key：对参数规范化后取 hash 前缀（≤16 字符），随 HTML 一起下发到前端 */
    val blockKey = varchar("block_key", 32)

    /** 股票代码（带交易所后缀，如 000001.SZ） */
    val tsCode = varchar("ts_code", 32)

    /** K 线周期：DAY / WEEK / MONTH / MIN_60 / MIN_30 / MIN_15 / MIN_5 */
    val period = varchar("period", 16)

    /** 开始日期 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss，nullable */
    val startDate = varchar("start_date", 32).nullable()

    /** 结束日期 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss，nullable */
    val endDate = varchar("end_date", 32).nullable()

    /** 限制条数，nullable */
    val limitCount = integer("limit_count").nullable()

    /** 指标列表（逗号分隔，如 "MA20,VOLUME"） */
    val indicators = varchar("indicators", 128).nullable()

    /** 是否前复权（与 CandleSubscribeRequest 对齐） */
    val useAdjusted = bool("use_adjusted").default(true)

    /** 创建时间 */
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    init {
        uniqueIndex("uq_share_block", shareToken, blockKey)
    }
}
