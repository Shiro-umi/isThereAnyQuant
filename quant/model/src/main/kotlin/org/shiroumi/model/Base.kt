package org.shiroumi.model

import org.ktorm.entity.Entity
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible


@Suppress("UNCHECKED_CAST")
interface ModelTypeBridge<T : Entity<T>> : KPropertyReader {
    val targetClass: KClass<T>
    fun convert(): T {
        val inProperties = this::class.memberProperties.onEach { f -> f.isAccessible = true }
        val outProperties = hashMapOf<String, KProperty1<out Any, *>>()
        targetClass.memberProperties.map { f ->
            outProperties[f.name] = f
            f.isAccessible = true
        }
        val f = targetClass.companionObject?.objectInstance as? Entity.Factory<T>
            ?: throw Exception("database type must have a Entity.Factory")
        return f().run {
            f entity@{
                inProperties.forEach { ip ->
                    val op = outProperties[ip.name]
                    (op as? KMutableProperty<T>)?.setter?.call(this, ip.readFrom(this@ModelTypeBridge))
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
interface KPropertyReader {
    fun KProperty1<out Any, *>.readFrom(from: Any): Any {
        return (getter as KProperty1.Getter<Any, Any>)(from)
    }
}