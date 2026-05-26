package org.shiroumi.database.agent.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.agent.table.AgentShareKlineAllowlistTable
import org.shiroumi.database.commonDb
import org.shiroumi.database.transaction

/**
 * K 线分享白名单 Repository。
 *
 * 写入时机：在 [AgentAnalysisResultRepository.grantShareToken] 成功后，
 * 由路由层解析 contentMd 中所有 quant-kline 块的参数并批量插入。
 *
 * 读取时机：匿名 K 线接口收到请求时，按 (shareToken, blockKey) 精确查回参数，
 * 严格校验请求参数与存储参数一致后再放行去 CandleDataFacade 取数。
 */
data class ShareKlineEntry(
    val shareToken: String,
    val blockKey: String,
    val tsCode: String,
    val period: String,
    val startDate: String?,
    val endDate: String?,
    val limitCount: Int?,
    val indicators: String?,
    val useAdjusted: Boolean,
)

object AgentShareKlineAllowlistRepository {

    /**
     * 幂等批量写入：同一 (shareToken, blockKey) 已存在则跳过。
     */
    fun upsertBatch(entries: List<ShareKlineEntry>) {
        if (entries.isEmpty()) return
        commonDb.transaction(AgentShareKlineAllowlistTable) {
            val token = entries.first().shareToken
            val existingKeys = AgentShareKlineAllowlistTable
                .selectAll()
                .where { AgentShareKlineAllowlistTable.shareToken eq token }
                .map { it[AgentShareKlineAllowlistTable.blockKey] }
                .toSet()

            entries.filter { it.blockKey !in existingKeys }.forEach { e ->
                AgentShareKlineAllowlistTable.insert {
                    it[shareToken] = e.shareToken
                    it[blockKey] = e.blockKey
                    it[tsCode] = e.tsCode
                    it[period] = e.period
                    it[startDate] = e.startDate
                    it[endDate] = e.endDate
                    it[limitCount] = e.limitCount
                    it[indicators] = e.indicators
                    it[useAdjusted] = e.useAdjusted
                }
            }
        }
    }

    fun find(shareToken: String, blockKey: String): ShareKlineEntry? {
        return commonDb.transaction(AgentShareKlineAllowlistTable) {
            AgentShareKlineAllowlistTable
                .selectAll()
                .where {
                    (AgentShareKlineAllowlistTable.shareToken eq shareToken) and
                        (AgentShareKlineAllowlistTable.blockKey eq blockKey)
                }
                .map(::toEntry)
                .firstOrNull()
        }
    }

    fun deleteByToken(shareToken: String) {
        commonDb.transaction(AgentShareKlineAllowlistTable) {
            AgentShareKlineAllowlistTable.deleteWhere {
                AgentShareKlineAllowlistTable.shareToken eq shareToken
            }
        }
    }

    private fun toEntry(row: ResultRow): ShareKlineEntry = ShareKlineEntry(
        shareToken = row[AgentShareKlineAllowlistTable.shareToken],
        blockKey = row[AgentShareKlineAllowlistTable.blockKey],
        tsCode = row[AgentShareKlineAllowlistTable.tsCode],
        period = row[AgentShareKlineAllowlistTable.period],
        startDate = row[AgentShareKlineAllowlistTable.startDate],
        endDate = row[AgentShareKlineAllowlistTable.endDate],
        limitCount = row[AgentShareKlineAllowlistTable.limitCount],
        indicators = row[AgentShareKlineAllowlistTable.indicators],
        useAdjusted = row[AgentShareKlineAllowlistTable.useAdjusted],
    )
}
