package org.shiroumi.database.datasource

import logger
import org.ktorm.entity.add
import org.ktorm.entity.map
import org.shiroumi.database.getStockBasic
import org.shiroumi.database.table.stockBasicSeq
import org.shiroumi.database.tushare
import org.shiroumi.model.database.StockBasicInfo

private val logger by logger("updateStockBasic")

suspend fun updateStockBasic() {
    logger.info("start update stock basic info..")
    val basicInfoSeq = stockBasicSeq
    val tsCodes = basicInfoSeq.map { it.tsCode }.toSet()
    tushare.getStockBasic()
        .onFail { msg -> logger.error("request stock basic info failed: $msg.") }
        .onSucceed { form ->
            logger.info("stock basic info request succeed, total: ${form.items.size}.")
            form.items.forEach { item ->
                val data = StockBasicInfo {
                    tsCode = "${item[0]}"
                    code = "${item[1]}"
                    name = "${item[2]}"
                    area = "${item[3]}"
                    industry = "${item[4]}"
                    cnSpell = "${item[5]}"
                    market = "${item[6]}"
                }
                if (data.tsCode in tsCodes) return@forEach
                stockBasicSeq.add(data)
            }
            logger.info("stock basic info updated to database.")
        }
}