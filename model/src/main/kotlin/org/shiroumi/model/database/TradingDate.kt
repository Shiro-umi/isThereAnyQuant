package org.shiroumi.model.database

import org.ktorm.entity.Entity
import org.shiroumi.ksp.BridgeSerialName
import org.shiroumi.ksp.DataClassBridge

@DataClassBridge
interface TradingDate : Entity<TradingDate> {
    @BridgeSerialName("trade_date")
    var date: String
    // factory
    companion object : Entity.Factory<TradingDate>()
}
