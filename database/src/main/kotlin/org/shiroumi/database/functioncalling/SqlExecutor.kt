package org.shiroumi.database.functioncalling

import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction
import org.shiroumi.ksp.Description
import org.shiroumi.ksp.FunctionCall


@FunctionCall(description = "输入你希望执行的sql并返回结果")
suspend fun executeSql(
    @Description("需要执行的sql语句") sql: String
) = stockDb.transaction {
    println(sql)
    val lowLevelCx = connection.connection as java.sql.Connection
    val query = lowLevelCx.createStatement()
    val rs = query.executeQuery(sql)
    val res = mutableListOf<List<String>>()
    while (rs.next()) {
        val column = mutableListOf<String>()
        for (i in 1..rs.metaData.columnCount) {
            column.add("${rs.getObject(i)}")
        }
        res.add(column)
    }
    println(res)
    "$res"
}