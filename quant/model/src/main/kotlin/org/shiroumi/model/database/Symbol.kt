package org.shiroumi.model.database

import org.ktorm.entity.Entity
import org.shiroumi.ksp.BridgeIgnore
import org.shiroumi.ksp.DataClassBridge

@DataClassBridge
interface Symbol : Entity<Symbol> {
    var code: String
    var name: String
    @BridgeIgnore
    var type: String        // stock/index/concept/board

    // factory
    companion object : Entity.Factory<Symbol>()
}
