package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.fundamental.StockFundamentalQuarterlyRepository
import org.shiroumi.database.fundamental.StockFundamentalRecord
import org.shiroumi.network.apis.getFinaIndicator
import org.shiroumi.network.apis.tushare

/**
 * 个股季频财务面板落库（stock_fundamental_quarterly）—— fina_indicator_vip 逐季整季全市场。
 *
 * 设计文档：private/research-docs/macro-fundamental-pressure-formula.html §6/§7/§9（H3）
 * 逐报告期（YYYY0331/0630/0930/1231）拉 VIP 整季全市场，按 (ts_code,end_date) upsert。
 *
 * 运行：./gradlew :ktor-server:syncFundamentalQuarterly -Dquant.fund.startY=2015 -Dquant.fund.endY=2025
 */
fun main() = runBlocking {
    ConfigManager.load()
    val startY = System.getProperty("quant.fund.startY")?.toIntOrNull() ?: 2015
    val endY = System.getProperty("quant.fund.endY")?.toIntOrNull() ?: 2025
    println("# 个股季频财务落库 stock_fundamental_quarterly  $startY ~ $endY")

    val quarters = listOf("0331", "0630", "0930", "1231")
    var totalRows = 0
    for (y in startY..endY) {
        for (q in quarters) {
            val period = "$y$q"
            try {
                val form = tushare.getFinaIndicator(useVip = true, period = period).check() ?: continue
                val fields = form.fields
                fun idx(name: String) = fields.indexOf(name)
                val iTs = idx("ts_code"); val iAnn = idx("ann_date"); val iEnd = idx("end_date")
                val iRoe = idx("roe"); val iRoeDt = idx("roe_dt"); val iEps = idx("eps")
                val iNp = idx("netprofit_yoy"); val iDtNp = idx("dt_netprofit_yoy")
                val iTr = idx("tr_yoy"); val iOr = idx("or_yoy")
                val iOcfS = idx("ocf_to_sales"); val iOcfps = idx("ocfps")

                val records = form.items.mapNotNull { col ->
                    fun d(i: Int) = if (i >= 0) col.getOrNull(i)?.toDoubleOrNull() else null
                    fun s(i: Int) = if (i >= 0) col.getOrNull(i)?.takeIf { !it.isNullOrBlank() && it != "null" } else null
                    val ts = s(iTs) ?: return@mapNotNull null
                    StockFundamentalRecord(
                        tsCode = ts,
                        endDate = s(iEnd) ?: period,
                        annDate = s(iAnn),
                        roe = d(iRoe), roeDt = d(iRoeDt), eps = d(iEps),
                        netprofitYoy = d(iNp), dtNetprofitYoy = d(iDtNp),
                        trYoy = d(iTr), orYoy = d(iOr),
                        ocfToSales = d(iOcfS), ocfps = d(iOcfps),
                    )
                }
                StockFundamentalQuarterlyRepository.upsert(records)
                totalRows += records.size
                println("- $period: ${records.size} 行")
            } catch (e: Exception) {
                println("- $period: error=${e.message}")
            }
        }
    }
    val total = StockFundamentalQuarterlyRepository.count()
    println("# 落库完成：本次 upsert $totalRows 行 → 表内现有 $total 行")
}
