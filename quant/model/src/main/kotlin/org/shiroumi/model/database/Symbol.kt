package org.shiroumi.model.database

import org.ktorm.entity.Entity

// stock/index/concept/board Symbol

interface Symbol : Entity<Symbol> {
    var code: String
    var name: String
    var type: String        // stock/index/concept/board

    // factory
    companion object : Entity.Factory<Symbol>()
}
