package org.shiroumi.quant_kmp.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.shiroumi.quant_kmp.ui.core.mvi.MviViewModel
import org.shiroumi.quant_kmp.service.GlobalWebSocketClient
import org.shiroumi.quant_kmp.util.TokenManager
import org.shiroumi.quant_kmp.util.TokenRefreshHandler
import org.shiroumi.quant_kmp.util.UserInfo

class AuthViewModel(
    private val authRepository: IAuthRepository = AuthRepository(),
) : ViewModel(), MviViewModel<AuthContract.State, AuthContract.Action, AuthContract.Effect> {

    // ── State ────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(AuthContract.State())
    override val state: StateFlow<AuthContract.State> = _state.asStateFlow()

    // ── Effect ───────────────────────────────────────────────────────

    private val _effect = MutableSharedFlow<AuthContract.Effect>(extraBufferCapacity = 1)
    override val effect: Flow<AuthContract.Effect> = _effect

    // ── Init ─────────────────────────────────────────────────────────

    init {
        // 同步初始用户状态
        val initialUser = TokenManager.user.value
        _state.update { it.copy(
            user = initialUser,
            authStatus = if (initialUser != null) AuthContract.AuthStatus.Authenticated
                         else AuthContract.AuthStatus.Unauthenticated,
        ) }

        // 持续监听 TokenManager 用户变化
        viewModelScope.launch {
            TokenManager.user.collect { userInfo ->
                _state.update { it.copy(
                    user = userInfo,
                    authStatus = if (userInfo != null) AuthContract.AuthStatus.Authenticated
                                 else AuthContract.AuthStatus.Unauthenticated,
                ) }
            }
        }
    }

    // ── Dispatch ─────────────────────────────────────────────────────

    override fun dispatch(action: AuthContract.Action) {
        when (action) {
            is AuthContract.Action.CheckStatus -> checkAuthStatus()
            is AuthContract.Action.Login       -> login(action.username, action.password)
            is AuthContract.Action.Register    -> register(action.username, action.password, action.nickname)
            is AuthContract.Action.Logout      -> logout()
            is AuthContract.Action.ClearError  -> clearError()
        }
    }

    // ── Public API（供 AuthGate LaunchedEffect 调用）─────────────────

    fun checkAuthStatus() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, authStatus = AuthContract.AuthStatus.Loading) }
            consoleLog("[Auth] 开始检查登录状态...")

            try {
                TokenManager.restoreFromStorage()
                withTimeout(10_000) {
                    checkAuthStatusInternal()
                }
            } catch (e: TimeoutCancellationException) {
                consoleLog("[Auth] 检查超时")
                setUnauthenticated()
            } catch (e: Exception) {
                consoleLog("[Auth] 检查异常: ${e.message}")
                setUnauthenticated()
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    // ── Private logic ─────────────────────────────────────────────────

    private fun login(username: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, authStatus = AuthContract.AuthStatus.Loading) }

            try {
                // Web/PWA 没有独立的“记住我”控件，默认持久化 refresh cookie，避免部署重启或 PWA 恢复后掉登录态。
                val rememberMe = true
                val result = authRepository.login(username, password, rememberMe = rememberMe)
                val response = result.getOrNull()
                if (response != null) {
                    TokenManager.saveTokens(response)
                    _state.update { it.copy(authStatus = AuthContract.AuthStatus.Authenticated) }
                    _effect.emit(AuthContract.Effect.NavigateToMain)
                } else {
                    val message = result.exceptionOrNull().toAuthMessage()
                    _state.update { it.copy(
                        authStatus = AuthContract.AuthStatus.Error,
                        errorMessage = message,
                    ) }
                    _effect.emit(AuthContract.Effect.ShowError(message))
                }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun register(username: String, password: String, nickname: String?) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            try {
                authRepository.register(username, password, nickname)
                    .onSuccess {
                        // 注册成功：切回未认证态，让用户登录
                        _state.update { it.copy(authStatus = AuthContract.AuthStatus.Unauthenticated) }
                    }
                    .onFailure { error ->
                        val message = error.toAuthMessage()
                        _state.update { it.copy(
                            authStatus = AuthContract.AuthStatus.Error,
                            errorMessage = message,
                        ) }
                        _effect.emit(AuthContract.Effect.ShowError(message))
                    }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun logout() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            try {
                val token = TokenManager.getAccessToken()
                    ?: TokenRefreshHandler.refresh(force = true).getOrNull()
                token?.let {
                    authRepository.logout(
                        accessToken = it,
                        refreshToken = TokenManager.getRefreshToken(),
                        logoutAllDevices = false,
                    )
                }
                TokenManager.clearTokens()
                GlobalWebSocketClient.disconnect()
                _state.update { it.copy(authStatus = AuthContract.AuthStatus.Unauthenticated) }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun clearError() {
        _state.update { state ->
            if (state.authStatus == AuthContract.AuthStatus.Error) {
                state.copy(authStatus = AuthContract.AuthStatus.Unauthenticated, errorMessage = null)
            } else {
                state
            }
        }
    }

    private suspend fun checkAuthStatusInternal() {
        val accessToken = TokenManager.getAccessToken()
        consoleLog("[Auth] Access Token: ${if (accessToken != null) "存在" else "不存在"}")

        if (accessToken != null) {
            val valid = validateTokenWithTimeout(accessToken)
            consoleLog("[Auth] Access Token 验证: ${if (valid) "有效" else "无效"}")
            if (valid) {
                _state.update { it.copy(authStatus = AuthContract.AuthStatus.Authenticated) }
                return
            }
        }

        consoleLog("[Auth] 尝试刷新 Token...")
        val refreshed = tryRefreshWithTimeout()
        consoleLog("[Auth] 刷新结果: ${if (refreshed) "成功" else "失败"}")
        if (refreshed) {
            _state.update { it.copy(authStatus = AuthContract.AuthStatus.Authenticated) }
        } else {
            setUnauthenticated()
        }
    }

    private suspend fun validateTokenWithTimeout(token: String): Boolean = try {
        withTimeout(3_000) {
            val result = authRepository.getCurrentUser(token)
            result.getOrNull()?.let { profile -> TokenManager.updateUser(profile.toUserInfo()) }
            result.isSuccess
        }
    } catch (e: Exception) {
        false
    }

    private suspend fun tryRefreshWithTimeout(): Boolean = try {
        withTimeout(3_000) {
            val refreshResult = TokenRefreshHandler.refresh(force = true)
            consoleLog("[Auth] Token 刷新结果: ${refreshResult.isSuccess}")
            TokenRefreshHandler.cleanup()

            if (refreshResult.isSuccess) {
                val newToken = refreshResult.getOrNull() ?: run {
                    consoleLog("[Auth] 刷新返回的 token 为空")
                    return@withTimeout false
                }
                val userResult = authRepository.getCurrentUser(newToken)
                consoleLog("[Auth] 获取用户信息结果: ${userResult.isSuccess}")
                userResult.getOrNull()?.let { profile ->
                    TokenManager.updateUser(profile.toUserInfo())
                    consoleLog("[Auth] 用户信息已更新: ${profile.username}")
                }
                userResult.isSuccess
            } else {
                consoleLog("[Auth] 刷新失败: ${refreshResult.exceptionOrNull()?.message}")
                false
            }
        }
    } catch (e: Exception) {
        consoleLog("[Auth] tryRefreshWithTimeout 异常: ${e.message}")
        false
    }

    private suspend fun setUnauthenticated() {
        TokenManager.clearTokens()
        GlobalWebSocketClient.disconnect()
        _state.update { it.copy(authStatus = AuthContract.AuthStatus.Unauthenticated) }
        consoleLog("[Auth] 设置为未认证状态")
    }

    private fun consoleLog(message: String) = println(message)

    private fun Throwable?.toAuthMessage(): String = when (this) {
        is AuthException -> message
        else -> this?.message?.takeIf { it.isNotBlank() } ?: "网络错误，请稍后重试"
    }

    private fun model.auth.UserProfile.toUserInfo(): UserInfo = UserInfo(
        id = id,
        username = username,
        nickname = nickname,
        avatar = avatar,
        roles = roles,
    )
}
