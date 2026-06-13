package org.shiroumi.database.common.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.DataUpdateStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.commonDb
import org.shiroumi.database.common.table.SystemStatusTable
import org.shiroumi.database.transaction
import utils.logger

private val logger by logger("SystemStatusRepository")
private val json = Json { ignoreUnknownKeys = true }

object SystemStatusRepository {

    /**
     * 确保表结构存在
     */
    fun ensureSchema() {
        commonDb.transaction(SystemStatusTable) {
            SchemaUtils.createMissingTablesAndColumns(SystemStatusTable)
        }
    }
    
    /**
     * 保存数据更新状态
     */
    fun saveDataUpdateStatus(status: DataUpdateStatus) {
        val statusJson = json.encodeToString(status)
        commonDb.transaction(SystemStatusTable) {
            // 先删除旧键值对再插入，规避某些 Exposed 版本中 MySQL upsert 的兼容性问题
            SystemStatusTable.deleteWhere { SystemStatusTable.key eq SystemStatusTable.KEY_DATA_UPDATE_STATUS }
            SystemStatusTable.insert {
                it[key] = SystemStatusTable.KEY_DATA_UPDATE_STATUS
                it[value] = statusJson
            }
        }
    }
    
    /**
     * 获取最后保存的数据更新状态
     */
    fun getDataUpdateStatus(): DataUpdateStatus? {
        return try {
            commonDb.transaction(SystemStatusTable) {
                SystemStatusTable.selectAll()
                    .where { SystemStatusTable.key eq SystemStatusTable.KEY_DATA_UPDATE_STATUS }
                    .singleOrNull()?.get(SystemStatusTable.value)?.let {
                        json.decodeFromString<DataUpdateStatus>(it)
                    }
            }
        } catch (e: Exception) {
            logger.error("Failed to load DataUpdateStatus from DB: ${e.message}")
            null
        }
    }
}
