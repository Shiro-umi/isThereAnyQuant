package org.shiroumi.database.common.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.batchReplace
import org.shiroumi.database.*
import org.shiroumi.database.common.table.IndexTable
import org.shiroumi.database.index.updater.fetchIndexDailyCandle
import org.shiroumi.network.apis.TushareForm
import org.shiroumi.network.apis.getIndexBasic
import org.shiroumi.network.apis.tushare
import utils.ScheduledTasks
import utils.logger

private val logger by logger("IndexUpdater")

suspend fun updateIndex() = runCatching {
    logger.info("start update index basic information..")
    val basic = fetchIndexBasic() ?: throw Exception("failed to fetch index basic information")
    logger.accept("index basic information updated successfully!")

    val scheduledTasks = ScheduledTasks<Unit>(frequency = 200)
    basic.items.forEach { (tsCode, _, _, _, _, _, _, listDate) ->
        scheduledTasks.emit(tag = "$tsCode") {
            fetchIndexDailyCandle(tsCode = "$tsCode", listDate = listDate)
        }
    }
    scheduledTasks.schedule().collect { (tag, _) -> logger.info("tag [$tag] done.") }
}.onFailure { t ->
    t.printStackTrace()
}

/**
 * @see <a href="https://tushare.pro/document/2?doc_id=94">指数基本信息</a>
 */
private suspend fun fetchIndexBasic(): TushareForm? = withContext(Dispatchers.IO) {
    val res = tushare.getIndexBasic(category = "综合指数").check() ?: return@withContext null
    commonDb.transaction(IndexTable, log = false) {
        IndexTable.batchReplace(res.items) { (tsCode, name, market, publisher, category, baseDate, basePoint, listDate) ->
            this[IndexTable.tsCode] = "$tsCode"
            this[IndexTable.name] = "$name"
            this[IndexTable.market] = "$market"
            this[IndexTable.publisher] = "$publisher"
            this[IndexTable.category] = category
            this[IndexTable.baseDate] = baseDate
            this[IndexTable.basePoint] = "${basePoint ?: 0}".toFloat()
            this[IndexTable.listDate] = listDate
        }
    }
    return@withContext res
}
