package org.shiroumi.quant_kmp.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.shiroumi.quant_kmp.feature.auth.AuthRepository
import org.shiroumi.quant_kmp.feature.auth.IAuthRepository

/**
 * Token 刷新处理器
 * 防止并发请求同时触发多次 Token 刷新
 */
object TokenRefreshHandler {

    private val mutex = Mutex()
    private val authRepository: IAuthRepository = AuthRepository()

    /**
     * 执行 Token 刷新
     * 如果已有刷新在进行中，等待其结果而不并发请求。
     * 不使用 GlobalScope，保证调用方超时/取消时能取消底层请求。
     */
    suspend fun refresh(force: Boolean = false): Result<String> = mutex.withLock {
        if (!force) {
            TokenManager.getAccessToken()?.let { return Result.success(it) }
        }
        performRefresh()
    }

    /**
     * 刷新完成后清理
     * 兼容旧调用点；刷新任务已经由结构化并发管理，不再需要手动清理。
     */
    fun cleanup() {
    }

    /**
     * 执行实际的刷新操作
     */
    private suspend fun performRefresh(): Result<String> {
        consoleLog("[TokenRefresh] 开始刷新 Token...")
        return try {
            val sessionVersion = TokenManager.currentSessionVersion()
            val result = authRepository.refreshToken(TokenManager.getRefreshToken())
            consoleLog("[TokenRefresh] 刷新结果: ${result.isSuccess}, 异常: ${result.exceptionOrNull()?.message}")
            val response = result.getOrElse { return Result.failure(it) }
            val updated = TokenManager.updateAccessToken(
                newToken = response.accessToken,
                expiresIn = response.expiresIn,
                expectedSessionVersion = sessionVersion,
            )
            if (!updated) {
                return Result.failure(IllegalStateException("Auth session changed during token refresh"))
            }
            Result.success(response.accessToken)
        } catch (e: Exception) {
            consoleLog("[TokenRefresh] 刷新异常: ${e.message}")
            Result.failure(e)
        }
    }

    private fun consoleLog(message: String) {
        println(message)
    }
}
