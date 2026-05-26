package org.shiroumi.database.common.table

import org.jetbrains.exposed.v1.core.Table

/**
 * 系统状态表
 * 用于存储全局系统配置、状态等 K-V 数据
 */
object SystemStatusTable : Table("system_status") {
    val key = varchar("key", 64)
    val value = text("value")
    
    override val primaryKey = PrimaryKey(key)
    
    // 预定义的 Key
    const val KEY_DATA_UPDATE_STATUS = "data_update_status"
}
