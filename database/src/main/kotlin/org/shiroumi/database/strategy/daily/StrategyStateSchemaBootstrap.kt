package org.shiroumi.database.strategy.daily

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.shiroumi.database.stockDb
import org.shiroumi.database.strategy.daily.table.DailyMarketSentimentStateTable
import org.shiroumi.database.strategy.daily.table.DailyMarketSentimentTable
import org.shiroumi.database.strategy.daily.table.DailyProfitPredictionSelectionTable
import org.shiroumi.database.strategy.daily.table.DailyStrategyAuditTable
import org.shiroumi.database.strategy.daily.table.SentimentRuntimeSeedTable
import org.shiroumi.database.transaction

/**
 * 对齐策略主流程运行时状态表结构。
 *
 * 当前工程还没有统一的 SQL migration 执行入口，而这些表已经进入正式业务路径：
 * - 盘后写入日频情绪滚动状态
 * - 次日盘中恢复 runtime seed
 * - backfill 顺推也会复用
 *
 * 因此这里在进程启动时显式完成一次 schema 对齐，避免首次业务写入时才暴露
 * TEXT/MEDIUMTEXT 不匹配的问题。
 */
object StrategyStateSchemaBootstrap {
    @Volatile
    private var ensured = false

    fun ensureSchema() {
        if (ensured) return
        synchronized(this) {
            if (ensured) return
            stockDb.transaction(
                DailyMarketSentimentStateTable,
                SentimentRuntimeSeedTable,
                DailyMarketSentimentTable,
                DailyProfitPredictionSelectionTable,
                DailyStrategyAuditTable,
                log = false
            ) {
                SchemaUtils.createMissingTablesAndColumns(
                    DailyMarketSentimentStateTable,
                    SentimentRuntimeSeedTable,
                    DailyMarketSentimentTable,
                    DailyProfitPredictionSelectionTable,
                    DailyStrategyAuditTable,
                )
                exec(
                    """
                    ALTER TABLE ${DailyMarketSentimentStateTable.tableName}
                        MODIFY COLUMN ${DailyMarketSentimentStateTable.sampleCodesJson.name} MEDIUMTEXT NOT NULL,
                        MODIFY COLUMN ${DailyMarketSentimentStateTable.symbolStatesJson.name} MEDIUMTEXT NOT NULL,
                        MODIFY COLUMN ${DailyMarketSentimentStateTable.bullRatioHistoryJson.name} MEDIUMTEXT NOT NULL,
                        MODIFY COLUMN ${DailyMarketSentimentStateTable.marketVolHistoryJson.name} MEDIUMTEXT NOT NULL,
                        MODIFY COLUMN ${DailyMarketSentimentStateTable.accelHistoryJson.name} MEDIUMTEXT NOT NULL,
                        MODIFY COLUMN ${DailyMarketSentimentStateTable.combinedHistoryJson.name} MEDIUMTEXT NOT NULL
                    """.trimIndent()
                )
                exec(
                    """
                    ALTER TABLE ${SentimentRuntimeSeedTable.tableName}
                        MODIFY COLUMN ${SentimentRuntimeSeedTable.sampleCodesJson.name} MEDIUMTEXT NOT NULL,
                        MODIFY COLUMN ${SentimentRuntimeSeedTable.symbolStatesJson.name} MEDIUMTEXT NOT NULL,
                        MODIFY COLUMN ${SentimentRuntimeSeedTable.bullRatioWindowJson.name} MEDIUMTEXT NOT NULL,
                        MODIFY COLUMN ${SentimentRuntimeSeedTable.marketVolWindowJson.name} MEDIUMTEXT NOT NULL,
                        MODIFY COLUMN ${SentimentRuntimeSeedTable.accelWindowJson.name} MEDIUMTEXT NOT NULL,
                        MODIFY COLUMN ${SentimentRuntimeSeedTable.combinedHistoryJson.name} MEDIUMTEXT NOT NULL
                    """.trimIndent()
                )
                relaxLegacyAuditColumns()
                backfillAuditPositionJsonFromLegacyCsv()
            }
            ensured = true
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.relaxLegacyAuditColumns() {
        val auditTableName = DailyStrategyAuditTable.tableName
        val legacyAuditColumns = listOf("newly_selected", "dropped", "current_positions")
            .filter { columnExists(auditTableName, it) }
        if (legacyAuditColumns.isNotEmpty()) {
            val modifications = legacyAuditColumns.joinToString(",\n") { column ->
                "                    MODIFY COLUMN $column VARCHAR(1024) NULL"
            }
            exec(
                """
                ALTER TABLE $auditTableName
$modifications
                """.trimIndent()
            )
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.backfillAuditPositionJsonFromLegacyCsv() {
        val tableName = DailyStrategyAuditTable.tableName
        if (
            !columnExists(tableName, "newly_selected") ||
            !columnExists(tableName, "dropped") ||
            !columnExists(tableName, "current_positions")
        ) {
            return
        }
        exec(
            """
            UPDATE $tableName
            SET ${DailyStrategyAuditTable.newlySelectedJson.name} = CASE
                    WHEN newly_selected IS NULL OR newly_selected = '' THEN '[]'
                    ELSE CONCAT('["', REPLACE(newly_selected, ',', '","'), '"]')
                END
            WHERE ${DailyStrategyAuditTable.newlySelectedJson.name} IS NULL
            """.trimIndent()
        )
        exec(
            """
            UPDATE $tableName
            SET ${DailyStrategyAuditTable.droppedJson.name} = CASE
                    WHEN dropped IS NULL OR dropped = '' THEN '[]'
                    ELSE CONCAT('["', REPLACE(dropped, ',', '","'), '"]')
                END
            WHERE ${DailyStrategyAuditTable.droppedJson.name} IS NULL
            """.trimIndent()
        )
        exec(
            """
            UPDATE $tableName
            SET ${DailyStrategyAuditTable.currentPositionsJson.name} = CASE
                    WHEN current_positions IS NULL OR current_positions = '' THEN '[]'
                    ELSE CONCAT('["', REPLACE(current_positions, ',', '","'), '"]')
                END
            WHERE ${DailyStrategyAuditTable.currentPositionsJson.name} IS NULL
            """.trimIndent()
        )
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.columnExists(tableName: String, columnName: String): Boolean {
        return exec(
            """
            SELECT COUNT(*)
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = '$tableName'
              AND COLUMN_NAME = '$columnName'
            """.trimIndent()
        ) { rs ->
            if (rs.next()) rs.getInt(1) > 0 else false
        } ?: false
    }
}
