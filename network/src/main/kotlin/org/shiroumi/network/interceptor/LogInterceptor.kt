package org.shiroumi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

// 可选：日志拦截器
class LoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        println("Request: ${request.url}, method: ${request.method}, body: ${request.body?.contentType()}")
        val response = chain.proceed(request)
        return response
    }
}