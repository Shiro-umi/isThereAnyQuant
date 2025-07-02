package org.shiroumi.network

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