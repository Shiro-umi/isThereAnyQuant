package org.shiroumi.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty

abstract class ApiDelegate<T> {

    abstract val clazz: Class<T>

    abstract val baseUrl: String

    operator fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T {
        return createRetrofit(baseUrl).create(clazz)
    }
}

inline fun <reified T> tushare() = object : ApiDelegate<T>() {
    override val clazz = T::class.java

    @Suppress("HttpUrlsUsage")
    override val baseUrl: String = "http://api.tushare.pro/"
}

@Serializable
data class BaseTushare(
    @SerialName("request_id")
    val requestId: String,
    val code: String,
    private val data: TushareForm
) {
    fun onSucceed(action: (TushareForm) -> Unit): BaseTushare {
        if (code != "0") return this
        action(data)
        return this
    }

    fun onFail(action: (msg: String) -> Unit): BaseTushare {
        if (code == "0") return this
        action("request failed. code: $code")
        return this
    }
}

@Serializable
data class TushareForm(
    val fields: List<String>,
    val items: List<List<String?>>
)