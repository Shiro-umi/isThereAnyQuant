package org.shiroumi.database.common.updater

import org.jetbrains.exposed.v1.jdbc.batchReplace
import org.shiroumi.database.common.table.CalendarTable
import org.shiroumi.database.common.table.StockBasicTable
import org.shiroumi.database.commonDb
import org.shiroumi.database.transaction
import org.shiroumi.network.apis.getStockBasic
import org.shiroumi.network.apis.tushare
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.component3
import kotlin.collections.component4
import kotlin.time.ExperimentalTime

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
    println(form)
    val basic = form.items.map { col ->
        listOf("${col[0]}", "${col[2]}", "${col[7]}", "${col[8]}", "${col[11]}")
    }
    commonDb.transaction(StockBasicTable, log = false) {
        StockBasicTable.batchReplace(basic) { (tsCode, name, cnSpell, market, listStatus) ->
            this[StockBasicTable.tsCode] = tsCode
            this[StockBasicTable.name] = name
            this[StockBasicTable.cnSpell] = cnSpell
            this[StockBasicTable.market] = market
            this[StockBasicTable.listStatus] = listStatus
        }
    }
}.onFailure {
    it.printStackTrace()
}
