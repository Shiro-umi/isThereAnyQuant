package org.shiroumi.model.database

import org.ktorm.entity.Entity
import org.shiroumi.ksp.BridgeSerialName
import org.shiroumi.ksp.DataClassBridge

@DataClassBridge
interface Candle : Entity<Candle> {
    @BridgeSerialName("日期")
    var date: String
    @BridgeSerialName("开盘")
    var open: Double
    @BridgeSerialName("收盘")
    var close: Double
    @BridgeSerialName("最高")
    var high: Double
    @BridgeSerialName("最低")
    var low: Double
    @BridgeSerialName("成交量")
    var volume: Double
    @BridgeSerialName("成交额")
    var turnover: Double
    @BridgeSerialName("振幅")
    var amplitude: Double
    @BridgeSerialName("涨跌幅")
    var changePercent: Double
    @BridgeSerialName("涨跌额")
    var changeAmount: Double
    @BridgeSerialName("换手率")
    var turnoverRate: Double

    // factory
    companion object : Entity.Factory<Candle>()
}
