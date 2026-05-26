package org.shiroumi.quant_kmp.feature.auth

import model.auth.LoginResponse
import model.auth.RefreshTokenResponse
import model.auth.RegisterResponse
import model.auth.UserProfile

/**
 * 认证仓库接口
 * 声明认证相关的数据操作契约，供 ViewModel 依赖注入使用
 */
interface IAuthRepository {

    /**
     * 用户登录
     * @param username 用户名
     * @param password 密码
     * @param rememberMe 是否记住我
     * @return 登录结果
     */
    suspend fun login(
        username: String,
        password: String,
        rememberMe: Boolean = false,
    ): Result<LoginResponse>

    /**
     * 用户注册
     * @param username 用户名
     * @param password 密码
     * @param nickname 昵称（可选）
     * @return 注册结果
     */
    suspend fun register(
        username: String,
        password: String,
        nickname: String? = null,
    ): Result<RegisterResponse>

    /**
     * 刷新 Access Token
     * Web 端 Refresh Token 通过 Cookie 自动携带；Android 端可通过请求体传入。
     * @return 新 Token 结果
     */
    suspend fun refreshToken(refreshToken: String? = null): Result<RefreshTokenResponse>

    /**
     * 获取当前用户信息
     * @param accessToken Access Token
     * @return 用户信息
     */
    suspend fun getCurrentUser(accessToken: String): Result<UserProfile>

    /**
     * 修改密码
     * @param accessToken Access Token
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return 是否成功
     */
    suspend fun changePassword(
        accessToken: String,
        oldPassword: String,
        newPassword: String,
    ): Result<Unit>

    /**
     * 用户登出
     * @param accessToken Access Token
     * @param logoutAllDevices 是否登出所有设备
     * @return 是否成功
     */
    suspend fun logout(
        accessToken: String,
        refreshToken: String? = null,
        logoutAllDevices: Boolean = false,
    ): Result<Unit>
}
