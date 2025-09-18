package org.shiroumi.database.functioncalling

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.select
import org.shiroumi.database.stockDb
import org.shiroumi.database.table.AdjCandleTable
import org.shiroumi.database.table.DailyCandleTable
import org.shiroumi.database.table.StockTable
import org.shiroumi.database.transaction
import org.shiroumi.ksp.Description
import org.shiroumi.ksp.FunctionCall


@FunctionCall(description = "这个方法没有什么帮助，占位用的")
suspend fun executeSql(
    @Description("这个方法没有什么帮助，占位用的") sql: String
) = ""
//@FunctionCall(description = "输入你希望执行的sql并返回结果")
//suspend fun executeSql(
//    @Description("需要执行的sql语句") sql: String
//) = stockDb.transaction {
//    println(sql)
//    val lowLevelCx = connection.connection as java.sql.Connection
//    val query = lowLevelCx.createStatement()
//    val rs = query.executeQuery(sql)
//    val res = mutableListOf<List<String>>()
//    while (rs.next()) {
//        val column = mutableListOf<String>()
//        for (i in 1..rs.metaData.columnCount) {
//            column.add("${rs.getObject(i)}")
//        }
//        res.add(column)
//    }
//    println(res)
//    "$res"
//}

data class JoinedCandles(
    val tsCode: String,
    val name: String,
    val res: List<List<String>>
)

fun getJoinedCandles(tsCode: String, limit: Int): JoinedCandles {
    var name = ""
    val res = stockDb.transaction {
        DailyCandleTable.join(
            AdjCandleTable,
            JoinType.LEFT,
            additionalConstraint = { (DailyCandleTable.tsCode eq AdjCandleTable.tsCode) }
        ).join(
            StockTable,
            JoinType.LEFT,
            additionalConstraint = { (DailyCandleTable.tsCode eq StockTable.tsCode) }
        ).select(
            DailyCandleTable.tsCode,
            StockTable.name,
            DailyCandleTable.tradeDate,
            AdjCandleTable.closeQfq,
            AdjCandleTable.lowQfq,
            AdjCandleTable.highQfq,
            AdjCandleTable.openQfq,
            AdjCandleTable.volFq,
        )
            .where {
                (DailyCandleTable.tsCode eq tsCode) and (DailyCandleTable.tradeDate eq AdjCandleTable.tradeDate) and (DailyCandleTable.tsCode eq StockTable.tsCode)
            }
            .orderBy(DailyCandleTable.tradeDate, SortOrder.DESC)
            .limit(limit)
            .map { row ->
                name.ifBlank { name = row[StockTable.name] }
                listOf(
                    "tradeDate=${row[DailyCandleTable.tradeDate]}",
                    "closeQfq=${row[AdjCandleTable.closeQfq]}",
                    "lowQfq=${row[AdjCandleTable.lowQfq]}",
                    "highQfq=${row[AdjCandleTable.highQfq]}",
                    "openQfq=${row[AdjCandleTable.openQfq]}",
                    "volFq=${row[AdjCandleTable.volFq]}",
                )
            }
            .toList()
    }
    return JoinedCandles(tsCode = tsCode, name = name, res = res)
}