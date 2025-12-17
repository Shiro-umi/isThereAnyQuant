package org.shiroumi.network.interceptor

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException


// 可选：日志拦截器
class LoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        println("Request: ${request.url}, method: ${request.method}, body: ${request.body?.content}")
        runCatching {
            val response = chain.proceed(request)
            if (!response.isSuccessful) {
                val errorBodyString = response.body.string()
                println("Error occurs. request_body: ${request.body?.content}, http_code: ${response.code},  msg: ${response.message}")
                val contentType: MediaType? = response.body.contentType()
                val newResponseBody: ResponseBody = errorBodyString.toResponseBody(contentType)
                response.newBuilder()
                    .body(newResponseBody)
                    .build()
            } else {
                val bodyString = response.body.string()
//            println("HTTP Status Code: " + response.code)
//            println("Response Body: $bodyString")
                val contentType: MediaType? = response.body.contentType()
                val newResponseBody: ResponseBody = bodyString.toResponseBody(contentType)
                response.newBuilder()
                    .body(newResponseBody)
                    .build()
            }
        }.fold(
            onSuccess = { resp ->
                return resp
            },
            onFailure = { t ->
                println("Error occurs. request_body: ${request.body?.content}")
                throw t
            }
        )
    }

    private val RequestBody.content: String
        get() {
            try {
                val buffer = Buffer()
                writeTo(buffer)
                return buffer.readUtf8()
            } catch (e: IOException) {
                e.printStackTrace()
                return "${e.message}"
            }
        }
}
