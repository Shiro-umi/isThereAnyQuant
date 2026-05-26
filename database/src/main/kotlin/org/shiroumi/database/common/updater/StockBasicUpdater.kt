package org.shiroumi.database.common.updater

import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.shiroumi.database.common.table.StockBasicTable
import org.shiroumi.database.stockDb
import org.shiroumi.database.transaction
import org.shiroumi.network.apis.getStockBasic
import org.shiroumi.network.apis.tushare
import utils.logger
import kotlin.time.ExperimentalTime

private val logger by logger("updateStockBasic")

/**
 * 名称	        类型	     默认显示	描述
 * ts_code	    str	     Y	        TS代码
 * symbol	    str	     Y	        股票代码
 * name	        str	     Y	        股票名称
 * area	        str	     Y	        地域
 * industry	    str	     Y	        所属行业
 * fullname	    str	     N	        股票全称
 * enname	    str	     N	        英文全称
 * cnspell	    str	     Y	        拼音缩写
 * market	    str	     Y	        市场类型（主板/创业板/科创板/CDR）
 * exchange	    str	     N	        交易所代码
 * curr_type	str	     N	        交易货币
 * list_status	str	     N	        上市状态 L上市 D退市 P暂停上市
 * list_date	str	     Y	        上市日期
 * delist_date	str	     N	        退市日期
 * is_hs	    str	     N	        是否沪深港通标的，N否 H沪股通 S深股通
 * act_name	    str	     Y	        实控人名称
 * act_ent_type	str	     Y	        实控人企业性质
 */
@OptIn(ExperimentalTime::class)
suspend fun updateStockBasic() = runCatching {
    val form = tushare.getStockBasic().check() ?: return@runCatching
    val basic = form.items.map { col ->
        StockBasicPayload(
            tsCode = "${col[0]}",
            name = "${col[2]}",
            cnSpell = "${col[7]}",
            market = "${col[8]}",
            listStatus = "${col[11]}",
            listDate = col.getOrNull(12)?.takeIf { it.isNotBlank() && it != "null" },
            delistDate = col.getOrNull(13)?.takeIf { it.isNotBlank() && it != "null" },
        )
    }
    stockDb.transaction(StockBasicTable, log = false) {
        StockBasicTable.batchUpsert(basic) { item ->
            this[StockBasicTable.tsCode] = item.tsCode
            this[StockBasicTable.name] = item.name
            this[StockBasicTable.cnSpell] = item.cnSpell
            this[StockBasicTable.market] = item.market
            this[StockBasicTable.listStatus] = item.listStatus
            this[StockBasicTable.listDate] = item.listDate
            this[StockBasicTable.delistDate] = item.delistDate
        }
    }
    logger.accept("update stock basic done.")
}.onFailure {
    it.printStackTrace()
}

private data class StockBasicPayload(
    val tsCode: String,
    val name: String,
    val cnSpell: String,
    val market: String,
    val listStatus: String,
    val listDate: String?,
    val delistDate: String?,
)
