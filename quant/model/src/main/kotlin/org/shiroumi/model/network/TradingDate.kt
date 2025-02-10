package org.shiroumi.model.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.shiroumi.model.database.TradingDate
import kotlin.reflect.KClass

@Serializable
data class TradingDate(
    @SerialName("trade_date")
    var date: String
): ModelTypeBridge<TradingDate> {
    override val targetClass: KClass<TradingDate> = TradingDate::class
}