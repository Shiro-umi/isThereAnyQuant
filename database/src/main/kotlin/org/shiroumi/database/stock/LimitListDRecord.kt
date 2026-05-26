package org.shiroumi.database.stock

import kotlinx.datetime.LocalDate

data class LimitListDRecord(
    val tradeDate: LocalDate,
    val tsCode: String,
    val industry: String?,
    val name: String?,
    val close: Double?,
    val pctChg: Double?,
    val amount: Double?,
    val limitAmount: Double?,
    val floatMv: Double?,
    val totalMv: Double?,
    val turnoverRatio: Double?,
    val fdAmount: Double?,
    val firstTime: String?,
    val lastTime: String?,
    val openTimes: Int?,
    val upStat: String?,
    val limitTimes: Int?,
    val limitType: String,
)
