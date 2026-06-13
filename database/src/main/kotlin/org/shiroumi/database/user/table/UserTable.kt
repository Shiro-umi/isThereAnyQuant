package org.shiroumi.database.user.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import org.shiroumi.database.MAX_VARCHAR_LENGTH

/**
 * 系统用户表（common_db）
 * 表名: sys_user
 *
 * 仅保留账号密码登录所需的核心字段，不存储邮箱、手机号等扩展信息。
 */
object UserTable : UUIDTable("sys_user") {

    /** 登录账号 - 唯一且不可为空 */
    val username = varchar("username", 64).uniqueIndex()

    /** BCrypt 加密后的密码哈希 */
    val passwordHash = varchar("password_hash", MAX_VARCHAR_LENGTH)

    /** 显示名称 - 可为空 */
    val nickname = varchar("nickname", 64).nullable()

    /** 账户是否处于激活状态 */
    val isActive = bool("is_active").default(true)

    /** 连续登录失败次数 */
    val failedLoginAttempts = integer("failed_login_attempts").default(0)

    /** 账户锁定截止时间，null 表示未锁定 */
    val lockedUntil = datetime("locked_until").nullable()

    /** 持仓跟踪最早跟随日校准（yyyy-MM-dd），null 表示跟随模型完整持仓流 */
    val trackingFollowStartDate = varchar("tracking_follow_start_date", 10).nullable()

    /** 记录创建时间 */
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    /** 记录最后更新时间 */
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
}
