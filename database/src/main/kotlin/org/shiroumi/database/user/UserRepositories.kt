package org.shiroumi.database.user

import org.shiroumi.database.commonDb
import org.shiroumi.database.user.repository.RefreshTokenRepository
import org.shiroumi.database.user.repository.UserRepository

/**
 * 获取基于 commonDb 的 UserRepository 实例
 */
fun createUserRepository(): UserRepository = UserRepository(commonDb)

/**
 * 获取基于 commonDb 的 RefreshTokenRepository 实例
 */
fun createRefreshTokenRepository(): RefreshTokenRepository = RefreshTokenRepository(commonDb)

/**
 * 获取基于 commonDb 的 UserAgentConfigRepository 实例
 */
fun createUserAgentConfigRepository(): org.shiroumi.database.user.repository.UserAgentConfigRepository = org.shiroumi.database.user.repository.UserAgentConfigRepository(commonDb)
