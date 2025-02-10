package org.shiroumi.model.database

import org.ktorm.entity.Entity

interface Candle : Entity<Candle> {
    var date: String
    var open: Double
    var close: Double
    var high: Double
    var low: Double
    var volume: Double       // Int64 对应 Kotlin Long
    var turnover: Double
    var amplitude: Double     // 百分比值，如 5.5 表示 5.5%
    var changePercent: Double // 百分比值
    var changeAmount: Double
    var turnoverRate: Double   // 百分比值

    // factory
    companion object : Entity.Factory<Candle>()
}