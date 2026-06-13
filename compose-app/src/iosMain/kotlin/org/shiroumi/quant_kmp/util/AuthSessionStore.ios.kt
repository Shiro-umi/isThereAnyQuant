package org.shiroumi.quant_kmp.util

import kotlinx.serialization.encodeToString
import org.shiroumi.quant_kmp.AppJson
import platform.Foundation.NSUserDefaults

/**
 * iOS 认证会话持久化：NSUserDefaults。
 * 与 Android DataStore 同语义——原生 app 自己保管 refresh token，storesRefreshToken=true，
 * 刷新会话时优先用本地存的 refresh token（行为与 Web HttpOnly Cookie 刷新链路对齐）。
 */
actual object PlatformAuthSessionStore {

    actual val storesRefreshToken: Boolean = true

    private const val SESSION_KEY = "quant.auth.session"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    actual suspend fun load(): AuthSession? {
        val encoded = defaults.stringForKey(SESSION_KEY) ?: return null
        return runCatching { AppJson.decodeFromString<AuthSession>(encoded) }
            .getOrElse {
                clear()
                null
            }
    }

    actual suspend fun save(session: AuthSession) {
        runCatching {
            defaults.setObject(AppJson.encodeToString(session), forKey = SESSION_KEY)
        }
    }

    actual suspend fun clear() {
        runCatching { defaults.removeObjectForKey(SESSION_KEY) }
    }
}
