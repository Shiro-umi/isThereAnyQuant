package org.shiroumi.database.agent.repository

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.agent.table.AgentShareViewLogTable
import org.shiroumi.database.commonDb
import org.shiroumi.database.transaction

data class ShareViewStats(
    val viewCount: Long,
    val lastViewedAt: LocalDateTime?,
)

object AgentShareViewLogRepository {

    fun record(shareToken: String, ipHash: String?, userAgent: String?) {
        commonDb.transaction(AgentShareViewLogTable) {
            AgentShareViewLogTable.insert {
                it[AgentShareViewLogTable.shareToken] = shareToken
                it[AgentShareViewLogTable.ipHash] = ipHash
                it[AgentShareViewLogTable.userAgent] = userAgent?.take(256)
            }
        }
    }

    fun stats(shareToken: String): ShareViewStats {
        return commonDb.transaction(AgentShareViewLogTable) {
            val rows = AgentShareViewLogTable
                .selectAll()
                .where { AgentShareViewLogTable.shareToken eq shareToken }
                .orderBy(AgentShareViewLogTable.viewedAt, SortOrder.DESC)
                .toList()

            ShareViewStats(
                viewCount = rows.size.toLong(),
                lastViewedAt = rows.firstOrNull()?.get(AgentShareViewLogTable.viewedAt),
            )
        }
    }
}
