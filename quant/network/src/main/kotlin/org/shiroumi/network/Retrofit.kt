package org.shiroumi.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type


@OptIn(ExperimentalSerializationApi::class)
fun createRetrofit(): Retrofit {
    // 配置 JSON 序列化（忽略未知字段）
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true // 可选，宽松模式
    }

    // 创建 String 转换器工厂（处理纯文本响应）
    val stringConverterFactory = object : Converter.Factory() {
        override fun responseBodyConverter(
            type: Type,
            annotations: Array<Annotation>,
            retrofit: Retrofit
        ): Converter<ResponseBody, *>? {
            return if (type == String::class.java) {
                Converter<ResponseBody, String> { it.string() }
            } else {
                null
            }
        }
    }

    return Retrofit.Builder()
        .baseUrl("http://192.168.31.125:2000/api/public/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor(LoggingInterceptor()) // 可选，添加日志拦截器
                .build()
        )
        // 先添加 String 转换器，优先级高于 JSON
        .addConverterFactory(stringConverterFactory)
        // 添加 JSON 转换器（处理 application/json）
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}

// 可选：日志拦截器
class LoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        println("Request: ${request.url}")
        return chain.proceed(request)
    }
}
