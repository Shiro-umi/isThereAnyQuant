package org.shiroumi.quant_kmp.di

import io.ktor.client.HttpClient
import org.shiroumi.quant_kmp.AppJson
import org.shiroumi.quant_kmp.configureCommon
import org.shiroumi.quant_kmp.createPlatformHttpClient

/**
 * HTTP 客户端提供者
 * 统一管理系统中的 HttpClient 实例
 */
object HttpClientProvider {

    /**
     * JSON 序列化配置
     * 使用全局配置
     */
    val json = AppJson

    /**
     * 认证专用 HttpClient
     * 用于登录、注册、刷新 Token 等认证相关接口
     */
    val authClient: HttpClient by lazy {
        createPlatformHttpClient()
    }

    /**
     * API HttpClient
     * 用于需要认证的 API 调用
     */
    val apiClient: HttpClient by lazy {
        createPlatformHttpClient().also {
            org.shiroumi.quant_kmp.util.AuthInterceptor().configureClient(it)
        }
    }

    /**
     * 关闭所有客户端
     */
    fun closeAll() {
        authClient.close()
        apiClient.close()
    }
}
