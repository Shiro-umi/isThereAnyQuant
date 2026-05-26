package org.shiroumi.network

import kotlin.reflect.KProperty


/**
 * API 委托类 - 延迟创建 ApiClient
 */
abstract class ApiDelegate<T>(
    private val factory: (ApiClient) -> T
) {
    abstract val baseUrl: String

    private val client by lazy { ApiClient(baseUrl) }
    private val api by lazy { factory(client) }

    operator fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T = api
}

// ============================================
// API 实例创建函数
// ============================================

/**
 * Tushare API
 */
inline fun <reified T> tushare() = object : ApiDelegate<T>({ client ->
    @Suppress("UNCHECKED_CAST")
    when (T::class) {
        org.shiroumi.network.apis.TuShareApi::class -> org.shiroumi.network.apis.TuShareApiImpl(client) as T
        else -> throw IllegalArgumentException("Unknown API type: ${T::class}")
    }
}) {
    @Suppress("HttpUrlsUsage")
    override val baseUrl: String = "http://api.tushare.pro"
}

/**
 * Deepseek API
 */
inline fun <reified T> deepseek() = object : ApiDelegate<T>({ client ->
    @Suppress("UNCHECKED_CAST")
    when (T::class) {
        org.shiroumi.network.apis.LLMApi::class -> org.shiroumi.network.apis.LLMApiImpl(client) as T
        else -> throw IllegalArgumentException("Unknown API type: ${T::class}")
    }
}) {
    override val baseUrl: String = "https://api.deepseek.com"
}

/**
 * SiliconFlow API
 */
inline fun <reified T> siliconFlow() = object : ApiDelegate<T>({ client ->
    @Suppress("UNCHECKED_CAST")
    when (T::class) {
        org.shiroumi.network.apis.LLMApi::class -> org.shiroumi.network.apis.LLMApiImpl(client) as T
        else -> throw IllegalArgumentException("Unknown API type: ${T::class}")
    }
}) {
    override val baseUrl: String = "https://api.siliconflow.cn"
}

/**
 * Msg API
 */
inline fun <reified T> msgApi() = object : ApiDelegate<T>({ client ->
    @Suppress("UNCHECKED_CAST")
    when (T::class) {
        org.shiroumi.network.apis.MsgApi::class -> org.shiroumi.network.apis.MsgApiImpl(client) as T
        else -> throw IllegalArgumentException("Unknown API type: ${T::class}")
    }
}) {
    @Suppress("HttpUrlsUsage")
    override val baseUrl: String = "http://43.142.47.227:10002"
}
