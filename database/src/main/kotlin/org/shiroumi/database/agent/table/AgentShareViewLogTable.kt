package org.shiroumi.database.agent.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * 分享分析结果的匿名访问日志（common_db）
 *
 * 表名: agent_share_view_log
 *
 * 仅记录 HTML 主页 /s/{token} 的访问，不记录 K 线 API 的子请求
 * （避免一次报告浏览刷出几十次）。IP 经 SHA256 + salt 哈希后存储，
 * 不保留裸 IP。
 */
object AgentShareViewLogTable : UUIDTable("agent_share_view_log") {

    /** 分享 token */
    val shareToken = varchar("share_token", 32).index()

    /** 访问时间 */
    val viewedAt = datetime("viewed_at").defaultExpression(CurrentDateTime)

    /** 访问者 IP 的 SHA256(ip + salt) 前 32 位十六进制 */
    val ipHash = varchar("ip_hash", 64).nullable()

    /** User-Agent，截断到 256 字符 */
    val userAgent = varchar("user_agent", 256).nullable()
}
