package org.shiroumi.database.user.model

import kotlinx.datetime.LocalDateTime
import java.util.UUID

/**
 * 用户模型
 *
 * 对应 sys_user 表的精简数据类，仅包含账号密码登录所需的核心字段。
 *
 * @property id 用户唯一标识符（UUID）
 * @property username 登录账号
 * @property passwordHash BCrypt 加密后的密码哈希
 * @property nickname 显示名称，可为 null
 * @property isActive 账户是否处于激活状态
 * @property failedLoginAttempts 连续登录失败次数
 * @property lockedUntil 账户锁定截止时间，null 表示未锁定
 * @property trackingFollowStartDate 持仓跟踪最早跟随日校准（yyyy-MM-dd），null 表示跟随模型完整持仓流
 * @property createdAt 记录创建时间
 * @property updatedAt 记录最后更新时间
 */
data class UserModel(
    val id: UUID,
    val username: String,
    val passwordHash: String,
    val nickname: String?,
    val isActive: Boolean,
    val failedLoginAttempts: Int,
    val lockedUntil: LocalDateTime?,
    val trackingFollowStartDate: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
