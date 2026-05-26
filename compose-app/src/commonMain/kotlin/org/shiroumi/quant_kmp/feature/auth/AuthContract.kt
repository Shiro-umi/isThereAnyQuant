package org.shiroumi.quant_kmp.feature.auth

import org.shiroumi.quant_kmp.ui.core.mvi.UiAction
import org.shiroumi.quant_kmp.ui.core.mvi.UiEffect
import org.shiroumi.quant_kmp.ui.core.mvi.UiState
import org.shiroumi.quant_kmp.util.UserInfo

object AuthContract {

    // ── State ────────────────────────────────────────────────────────

    data class State(
        override val isLoading: Boolean = false,
        override val errorMessage: String? = null,
        val user: UserInfo? = null,
        val authStatus: AuthStatus = AuthStatus.Unauthenticated,
    ) : UiState

    enum class AuthStatus {
        Unauthenticated,
        Loading,
        Authenticated,
        Error,
    }

    // ── Action ───────────────────────────────────────────────────────

    sealed interface Action : UiAction {
        data object CheckStatus : Action
        data class Login(val username: String, val password: String) : Action
        data class Register(
            val username: String,
            val password: String,
            val nickname: String? = null,
        ) : Action
        data object Logout : Action
        data object ClearError : Action
    }

    // ── Effect ───────────────────────────────────────────────────────

    sealed interface Effect : UiEffect {
        data object NavigateToMain : Effect
        data class ShowError(val message: String) : Effect
    }
}
