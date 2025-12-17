package org.shiroumi.database.sw_index.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.batchReplace
import org.shiroumi.database.*
import org.shiroumi.database.sw_index.table.SwIndexDailyTable
import org.shiroumi.network.apis.getSwIndexDailyCandle
import org.shiroumi.network.apis.tushare
import utils.logger

private val logger by logger("SwIndexDailyCandleUpdater")

/**
 * @see <a href="https://tushare.pro/document/2?doc_id=327">申万行业日线行情</a>
 *
 * <table><thead><tr><th>名称</th><th>类型</th><th>默认显示</th><th>描述</th></tr></thead><tbody><tr><td>ts_code</td><td>str</td><td>Y</td><td>指数代码</td></tr><tr><td>trade_date</td><td>str</td><td>Y</td><td>交易日期</td></tr><tr><td>name</td><td>str</td><td>Y</td><td>指数名称</td></tr><tr><td>open</td><td>float</td><td>Y</td><td>开盘点位</td></tr><tr><td>low</td><td>float</td><td>Y</td><td>最低点位</td></tr><tr><td>high</td><td>float</td><td>Y</td><td>最高点位</td></tr><tr><td>close</td><td>float</td><td>Y</td><td>收盘点位</td></tr><tr><td>change</td><td>float</td><td>Y</td><td>涨跌点位</td></tr><tr><td>pct_change</td><td>float</td><td>Y</td><td>涨跌幅</td></tr><tr><td>vol</td><td>float</td><td>Y</td><td>成交量（万股）</td></tr><tr><td>amount</td><td>float</td><td>Y</td><td>成交额（万元）</td></tr><tr><td>pe</td><td>float</td><td>Y</td><td>市盈率</td></tr><tr><td>pb</td><td>float</td><td>Y</td><td>市净率</td></tr><tr><td>float_mv</td><td>float</td><td>Y</td><td>流通市值（万元）</td></tr><tr><td>total_mv</td><td>float</td><td>Y</td><td>总市值（万元）</td></tr></tbody></table>
 */
suspend fun fetchSwIndexDailyCandle(tsCode: String) {
    withContext(Dispatchers.Default) {
        logger.info("fetch sw index daily candle of $tsCode")
        val res = tushare.getSwIndexDailyCandle(tsCode = tsCode).check() ?: return@withContext
        val table = object : SwIndexDailyTable(tsCode = "`$tsCode`") {}
        swIndexDb.transaction(table, log = true) {
            table.batchReplace(res.items) { (tsCode, tradeDate, name, open, high, low, close, change, pctChange, vol, amount, pe, pb, floatMv, totalMv) ->
                this[table.tsCode] = "$tsCode"
                this[table.tradeDate] = "$tradeDate"
                this[table.name] = "$name"
                this[table.open] = "${open ?: -1}".toFloat()
                this[table.high] = "${high ?: -1}".toFloat()
                this[table.low] = "${low ?: -1}".toFloat()
                this[table.close] = "${close ?: -1}".toFloat()
                this[table.change] = "${change ?: -1}".toFloat()
                this[table.pctChange] = "${pctChange ?: -1}".toFloat()
                this[table.vol] = "${vol ?: -1}".toFloat()
                this[table.amount] = "${amount ?: -1}".toFloat()
                this[table.pe] = "${pe ?: -1}".toFloat()
                this[table.pb] = "${pb ?: -1}".toFloat()
                this[table.floatMv] = "${floatMv ?: -1}".toFloat()
                this[table.totalMv] = "${totalMv ?: -1}".toFloat()
            }
        }
        logger.accept("fetch sw index daily candle of $tsCode done.")
    }
}