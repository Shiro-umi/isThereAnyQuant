package org.shiroumi.network

import kotlin.reflect.KProperty

abstract class ApiDelegate<T> {

    abstract val clazz: Class<T>

    operator fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T {
        return createRetrofit().create(clazz)
    }
}

inline fun <reified T> api() = object : ApiDelegate<T>() {
    override val clazz = T::class.java
}