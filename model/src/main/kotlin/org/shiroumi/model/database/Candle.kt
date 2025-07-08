package org.shiroumi.model.database

import org.ktorm.entity.Entity
import org.shiroumi.ksp.BridgeSerialName
import org.shiroumi.ksp.DataClassBridge
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@DataClassBridge
interface Candle : Entity<Candle> {

    var tsCode: String
    var tradeDate: String
    var close: Float
    var open: Float
    var high: Float
    var low: Float
    var vol: Float
    var amount: Float
    var openHfq: Float
    var openQfq: Float
    var closeHfq: Float
    var closeQfq: Float
    var highHfq: Float
    var highQfq: Float
    var lowHfq: Float
    var lowQfq: Float

    companion object : Entity.Factory<Candle>()


}
