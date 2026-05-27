package org.shiroumi.database.sentiment.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

object SentimentFactorDailyTable : Table(name = "sentiment_factor_daily") {
    val tradeDate = date("trade_date")

    val a1 = double("A1").nullable()
    val a2 = double("A2").nullable()
    val a3 = double("A3").nullable()
    val a4 = double("A4").nullable()
    val a5 = double("A5").nullable()
    val a6 = double("A6").nullable()
    val a7 = double("A7").nullable()
    val a8 = double("A8").nullable()
    val a9a = double("A9a").nullable()
    val a9b = double("A9b").nullable()
    val a10 = double("A10").nullable()
    val a11 = double("A11").nullable()
    val a11a = double("A11a").nullable()
    val a12 = double("A12").nullable()

    val b1 = double("B1").nullable()
    val b3 = double("B3").nullable()
    val b3p = double("B3p").nullable()
    val b4 = double("B4").nullable()
    val b5 = double("B5").nullable()
    val b6 = double("B6").nullable()
    val b7 = double("B7").nullable()

    val c1 = double("C1").nullable()
    val c2 = double("C2").nullable()
    val c2p = double("C2p").nullable()
    val c3 = double("C3").nullable()
    val c4 = double("C4").nullable()
    val c5 = double("C5").nullable()
    val c6 = double("C6").nullable()
    val c7 = double("C7").nullable()

    val d1 = double("D1").nullable()
    val d2 = double("D2").nullable()
    val d3 = double("D3").nullable()
    val d4 = double("D4").nullable()
    val d5 = double("D5").nullable()
    val d6 = double("D6").nullable()
    val d7 = double("D7").nullable()

    val e1 = double("E1").nullable()
    val e2 = double("E2").nullable()

    val y1Raw = double("Y1_raw").nullable()
    val y2Raw = double("Y2_raw").nullable()
    val y3Raw = double("Y3_raw").nullable()
    val yComposite = double("Y_composite").nullable()
    val notes = varchar("notes", 512).nullable()

    override val primaryKey = PrimaryKey(tradeDate, name = "pk_sentiment_factor_daily")

    val factorColumns = linkedMapOf(
        "A1" to a1,
        "A2" to a2,
        "A3" to a3,
        "A4" to a4,
        "A5" to a5,
        "A6" to a6,
        "A7" to a7,
        "A8" to a8,
        "A9a" to a9a,
        "A9b" to a9b,
        "A10" to a10,
        "A11" to a11,
        "A11a" to a11a,
        "A12" to a12,
        "B1" to b1,
        "B3" to b3,
        "B3p" to b3p,
        "B4" to b4,
        "B5" to b5,
        "B6" to b6,
        "B7" to b7,
        "C1" to c1,
        "C2" to c2,
        "C2p" to c2p,
        "C3" to c3,
        "C4" to c4,
        "C5" to c5,
        "C6" to c6,
        "C7" to c7,
        "D1" to d1,
        "D2" to d2,
        "D3" to d3,
        "D4" to d4,
        "D5" to d5,
        "D6" to d6,
        "D7" to d7,
        "E1" to e1,
        "E2" to e2,
    )
}
