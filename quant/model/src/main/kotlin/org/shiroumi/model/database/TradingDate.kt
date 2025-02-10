package org.shiroumi.model.database

import org.ktorm.entity.Entity

// stock/index/concept/board Symbol

interface TradingDate : Entity<TradingDate> {
    var date: String
    // factory
    companion object : Entity.Factory<TradingDate>()
}
