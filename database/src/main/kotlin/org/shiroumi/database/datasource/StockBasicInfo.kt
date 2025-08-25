package org.shiroumi.database.datasource

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logger
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.shiroumi.database.getStockBasic
import org.shiroumi.database.stockDb
import org.shiroumi.database.table.StockTable
import org.shiroumi.database.transaction
import org.shiroumi.database.tushare

private val logger by logger("updateStockBasic")

suspend fun updateStockBasic() = withContext(
    context = Dispatchers.IO + CoroutineExceptionHandler { _, t -> t.printStackTrace() }
) {

    logger.info("start update stock basic info..")
    val items = tushare.getStockBasic().check()!!.items
    stockDb.transaction(StockTable) {
        StockTable.batchUpsert(items) { item ->
            set(StockTable.tsCode, "${item[0]}")
            set(StockTable.name, "${item[2]}")
            set(StockTable.area, "${item[3]}")
            set(StockTable.industry, "${item[4]}")
            set(StockTable.cnSpell, "${item[5]}")
            set(StockTable.market, "${item[6]}")
        }
    }
    logger.info("stock basic info updated.")
}