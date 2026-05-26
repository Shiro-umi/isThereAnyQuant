package org.shiroumi.database.agent

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.shiroumi.database.agent.table.AgentAnalysisResultTable
import org.shiroumi.database.agent.table.AgentShareKlineAllowlistTable
import org.shiroumi.database.agent.table.AgentShareViewLogTable
import org.shiroumi.database.commonDb
import org.shiroumi.database.transaction

/**
 * Agent 分析结果表结构启动对齐。
 *
 * 当前工程没有统一的 SQL migration 执行入口，而该表需要 MEDIUMTEXT 存储 Markdown 内容，
 * 因此在进程启动时显式完成一次 schema 对齐，避免首次写入时因 TEXT(64KB) 上限被截断。
 */
object AgentSchemaBootstrap {
    @Volatile
    private var ensured = false

    fun ensureSchema() {
        if (ensured) return
        synchronized(this) {
            if (ensured) return
            commonDb.transaction(AgentAnalysisResultTable, log = false) {
                SchemaUtils.createMissingTablesAndColumns(
                    AgentAnalysisResultTable,
                    AgentShareKlineAllowlistTable,
                    AgentShareViewLogTable,
                )
                exec(
                    """
                    ALTER TABLE ${AgentAnalysisResultTable.tableName}
                        MODIFY COLUMN ${AgentAnalysisResultTable.contentMd.name} MEDIUMTEXT
                    """.trimIndent()
                )
                exec(
                    """
                    ALTER TABLE ${AgentShareKlineAllowlistTable.tableName}
                        MODIFY COLUMN start_date VARCHAR(32) NULL,
                        MODIFY COLUMN end_date VARCHAR(32) NULL
                    """.trimIndent()
                )
            }
            ensured = true
        }
    }
}
