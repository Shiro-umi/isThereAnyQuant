package org.shiroumi.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
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
    val msg: String,
    private val data: TushareForm? = null
) {

    suspend fun check() = suspendCoroutine { c ->
        if (code != "0") {
            c.resumeWithException(Exception("request failed. code: $code, msg: $msg"))
            return@suspendCoroutine
        }
        c.resume(data)
    }
}

@Serializable
data class TushareForm(
    val fields: List<String>,
    val items: List<List<String?>>
) {
    fun toColumns(sortKey: String? = null): List<Column> {
        val sorted = sortKey?.let { key ->
            val keyIndex = fields.indexOf(sortKey)
            items.sortedBy { item -> item[keyIndex] }
        } ?: items
        return sorted.map { Column(fields = fields, items = it) }
    }
}

data class Column(
    val fields: List<String>,
    val items: List<String?>
) {

    infix fun provides(key: String) = items[fields.indexOf(key)] ?: ""
}