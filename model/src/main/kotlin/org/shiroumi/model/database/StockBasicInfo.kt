package org.shiroumi.model.database

import org.ktorm.entity.Entity
import org.shiroumi.ksp.DataClassBridge

@DataClassBridge
interface StockBasicInfo : Entity<StockBasicInfo> {
    var tsCode: String
    var code: String
    var name: String
    var area: String
    var industry: String
    var cnSpell: String
    var market: String

    // factory
    companion object : Entity.Factory<StockBasicInfo>()
}
