package org.shiroumi.database.agent.repository

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import model.agent.AgentAnalysisResultDto
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.shiroumi.database.agent.model.AgentAnalysisResultModel
import org.shiroumi.database.agent.table.AgentAnalysisResultTable
import org.shiroumi.database.commonDb
import org.shiroumi.database.transaction
import java.util.UUID

/**
 * Agent 分析结果数据访问层（common_db）
 *
 * 采用 object 单例风格，与策略域 Repository 保持一致。
 */
object AgentAnalysisResultRepository {

    fun save(model: AgentAnalysisResultModel): AgentAnalysisResultModel {
        return commonDb.transaction(AgentAnalysisResultTable) {
            val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
            AgentAnalysisResultTable.insert {
                it[id] = model.id
                it[userId] = model.userId
                it[tsCode] = model.tsCode
                it[analysisType] = model.analysisType
                it[sessionId] = model.sessionId
                it[title] = model.title
                it[contentMd] = model.contentMd
                it[metadataJson] = model.metadataJson
                it[tradeDate] = model.tradeDate
                it[createdAt] = now
                it[updatedAt] = now
            }
            model.copy(createdAt = now, updatedAt = now)
        }
    }

    fun findByUser(
        userId: UUID,
        tsCode: String? = null,
        type: String? = null,
        limit: Int = 20
    ): List<AgentAnalysisResultModel> {
        return commonDb.transaction(AgentAnalysisResultTable) {
            AgentAnalysisResultTable
                .selectAll()
                .where {
                    (AgentAnalysisResultTable.userId eq userId)
                        .andIfNotNull(tsCode) { AgentAnalysisResultTable.tsCode eq it }
                        .andIfNotNull(type) { AgentAnalysisResultTable.analysisType eq it }
                }
                .orderBy(AgentAnalysisResultTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map(::toModel)
        }
    }

    fun findById(id: UUID): AgentAnalysisResultModel? {
        return commonDb.transaction(AgentAnalysisResultTable) {
            AgentAnalysisResultTable
                .selectAll()
                .where { AgentAnalysisResultTable.id eq id }
                .map(::toModel)
                .firstOrNull()
        }
    }

    fun delete(id: UUID, userId: UUID): Boolean {
        return commonDb.transaction(AgentAnalysisResultTable) {
            val deleted = AgentAnalysisResultTable.deleteWhere {
                (AgentAnalysisResultTable.id eq id) and (AgentAnalysisResultTable.userId eq userId)
            }
            deleted > 0
        }
    }

    fun updateTitle(id: UUID, title: String?): Boolean {
        return commonDb.transaction(AgentAnalysisResultTable) {
            val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val updated = AgentAnalysisResultTable.update({ AgentAnalysisResultTable.id eq id }) {
                it[AgentAnalysisResultTable.title] = title
                it[updatedAt] = now
            }
            updated > 0
        }
    }

    /**
     * 为指定记录授予分享 token（幂等：已有 token 时直接返回旧 token）。
     *
     * @return 当前生效的 shareToken；若 id 对应记录不存在或不属于该用户返回 null
     */
    fun grantShareToken(
        id: UUID,
        userId: UUID,
        tokenIfAbsent: String,
        themeName: String? = null,
        isDark: Boolean? = null,
    ): String? {
        return commonDb.transaction(AgentAnalysisResultTable) {
            // 取整行而不是只取 share_token 列：share_token 为 NULL 时
            // 直接 map { it[shareToken] }.firstOrNull() 会和"行不存在"的 null 无法区分
            val row = AgentAnalysisResultTable
                .selectAll()
                .where { (AgentAnalysisResultTable.id eq id) and (AgentAnalysisResultTable.userId eq userId) }
                .firstOrNull()
                ?: return@transaction null

            val existing = row[AgentAnalysisResultTable.shareToken]
            if (existing != null) return@transaction existing

            val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
            AgentAnalysisResultTable.update({
                (AgentAnalysisResultTable.id eq id) and (AgentAnalysisResultTable.userId eq userId)
            }) {
                it[shareToken] = tokenIfAbsent
                it[sharedAt] = now
                it[shareTheme] = themeName
                it[shareBrightnessDark] = isDark
                it[updatedAt] = now
            }
            tokenIfAbsent
        }
    }

    fun findByShareToken(token: String): AgentAnalysisResultModel? {
        return commonDb.transaction(AgentAnalysisResultTable) {
            AgentAnalysisResultTable
                .selectAll()
                .where { AgentAnalysisResultTable.shareToken eq token }
                .map(::toModel)
                .firstOrNull()
        }
    }

    private fun toModel(row: org.jetbrains.exposed.v1.core.ResultRow): AgentAnalysisResultModel {
        return AgentAnalysisResultModel(
            id = row[AgentAnalysisResultTable.id].value,
            userId = row[AgentAnalysisResultTable.userId].value,
            tsCode = row[AgentAnalysisResultTable.tsCode],
            analysisType = row[AgentAnalysisResultTable.analysisType],
            sessionId = row[AgentAnalysisResultTable.sessionId],
            title = row[AgentAnalysisResultTable.title],
            contentMd = row[AgentAnalysisResultTable.contentMd],
            metadataJson = row[AgentAnalysisResultTable.metadataJson],
            tradeDate = row[AgentAnalysisResultTable.tradeDate],
            shareToken = row[AgentAnalysisResultTable.shareToken],
            sharedAt = row[AgentAnalysisResultTable.sharedAt],
            shareTheme = row[AgentAnalysisResultTable.shareTheme],
            shareBrightnessDark = row[AgentAnalysisResultTable.shareBrightnessDark],
            createdAt = row[AgentAnalysisResultTable.createdAt],
            updatedAt = row[AgentAnalysisResultTable.updatedAt],
        )
    }

    private fun <T> org.jetbrains.exposed.v1.core.Op<Boolean>.andIfNotNull(
        value: T?,
        block: (T) -> org.jetbrains.exposed.v1.core.Op<Boolean>
    ): org.jetbrains.exposed.v1.core.Op<Boolean> {
        return if (value != null) this.and(block(value)) else this
    }
}

fun AgentAnalysisResultModel.toDto(): AgentAnalysisResultDto = AgentAnalysisResultDto(
    id = this.id.toString(),
    tsCode = this.tsCode.takeIf { it.isNotBlank() },
    analysisType = this.analysisType.takeIf { it.isNotBlank() },
    title = this.title,
    contentMd = this.contentMd,
    metadataJson = this.metadataJson,
    tradeDate = this.tradeDate?.toString(),
    createdAt = this.createdAt.toString(),
    updatedAt = this.updatedAt.toString()
)
