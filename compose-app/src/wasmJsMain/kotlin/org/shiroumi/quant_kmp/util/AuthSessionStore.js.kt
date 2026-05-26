package org.shiroumi.quant_kmp.util

import kotlinx.browser.localStorage
import kotlinx.serialization.encodeToString
import org.shiroumi.quant_kmp.AppJson
import org.w3c.dom.get
import org.w3c.dom.set

actual object PlatformAuthSessionStore {

    actual val storesRefreshToken: Boolean = false

    private const val SESSION_KEY = "quant.auth.session"

    actual suspend fun load(): AuthSession? {
        val encoded = runCatching { localStorage[SESSION_KEY] }.getOrNull() ?: return null
        return runCatching { AppJson.decodeFromString<AuthSession>(encoded) }
            .getOrElse {
                clear()
                null
            }
    }

    actual suspend fun save(session: AuthSession) {
        // Refresh token 仍由浏览器 HttpOnly cookie 管理，不要写入 localStorage。
        val sanitized = session.copy(refreshToken = null)
        runCatching { localStorage[SESSION_KEY] = AppJson.encodeToString(sanitized) }
    }

    actual suspend fun clear() {
        runCatching { localStorage.removeItem(SESSION_KEY) }
    }
}
