package org.shiroumi.network.interceptor

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
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
        if (!response.isSuccessful) {
            val errorBodyString = response.body.string()
            println("HTTP Status Code: " + response.code)
            println("Error Response Body: $errorBodyString")
            // 重要：由于responseBody.string()已经消费了原响应的body，
            // 我们需要重新构建一个ResponseBody返回，否则调用方无法读取errorBody
            val contentType: MediaType? = response.body.contentType()
            val newResponseBody: ResponseBody = errorBodyString.toResponseBody(contentType)
            return response.newBuilder()
                .body(newResponseBody)
                .build()
        }
        return response
    }
}