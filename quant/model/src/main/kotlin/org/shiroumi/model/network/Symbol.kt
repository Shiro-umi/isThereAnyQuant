package org.shiroumi.model.network

import kotlinx.serialization.Serializable
import org.shiroumi.model.database.Symbol
import kotlin.reflect.KClass

@Serializable
data class Symbol(
    val code: String,
    val name: String
) : ModelTypeBridge<Symbol> {
    override val targetClass: KClass<Symbol> = Symbol::class
}
