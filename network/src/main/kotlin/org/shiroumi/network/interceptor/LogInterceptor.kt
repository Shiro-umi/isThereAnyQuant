package org.shiroumi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException

// 可选：日志拦截器
class LoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val buffer = Buffer()
        try {
            request.body?.writeTo(buffer)
            val requestBodyToString = buffer.readUtf8()
            println("Request: ${request.url}, method: ${request.method}, body: $requestBodyToString")
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val response = chain.proceed(request)
        return response
    }
}