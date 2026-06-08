package org.shiroumi.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.common.CalendarReader
import org.shiroumi.database.stock.TopListRecord
import org.shiroumi.database.stock.TopListRepository
import org.shiroumi.network.apis.getTopList
import org.shiroumi.network.apis.tushare

/**
 * 龙虎榜每日汇总落库（top_list）—— Tushare top_list 逐交易日全市场上榜股。
 *
 * 设计动机：方向极性维度（上榜事件 + 龙虎榜净买入方向），正交于个股量价强度。
 *
 * 并发策略对齐项目规范（见 SyncKplList）：
 *   coroutineScope + Semaphore(concurrency) + async(Dispatchers.IO) + awaitAll + 逐日重试。
 *   concurrency 默认 12（top_list 2000 积分接口，保守并发避免频控）。
 * 每日上榜股（数十~千条），同股同日多上榜原因时取首条；按 (ts_code, trade_date) upsert，可重入续传。
 *
 * 运行：./gradlew :ktor-server:syncTopList -Dquant.toplist.start=2009-01-01 -Dquant.toplist.end=2026-06-30
 */
fun main() = runBlocking {
    ConfigManager.load()
    val start = System.getProperty("quant.toplist.start")?.let { LocalDate.parse(it) } ?: LocalDate.parse("2009-01-01")
    val end = System.getProperty("quant.toplist.end")?.let { LocalDate.parse(it) } ?: LocalDate.parse("2026-06-30")
    val concurrency = System.getProperty("quant.toplist.concurrency")?.toIntOrNull() ?: 12
    val retries = System.getProperty("quant.toplist.retries")?.toIntOrNull() ?: 3
    println("# 龙虎榜日汇总落库 top_list  $start ~ $end  (并发=$concurrency)")

    val days = CalendarReader.getTradingDaysBetween(start, end)
    println("# 交易日数：${days.size}")
    val totalRows = AtomicInteger(0)
    val doneDays = AtomicInteger(0)

    coroutineScope {
        val semaphore = Semaphore(concurrency)
        days.map { day ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val yyyymmdd = "%04d%02d%02d".format(day.year, day.monthNumber, day.dayOfMonth)
                    val records = fetchTopListWithRetry(yyyymmdd, retries) ?: return@withPermit
                    TopListRepository.upsert(records)
                    totalRows.addAndGet(records.size)
                    val done = doneDays.incrementAndGet()
                    if (done % 200 == 0) println("- 进度 $done/${days.size}：累计 ${totalRows.get()} 行")
                }
            }
        }.awaitAll()
    }
    val total = TopListRepository.count()
    println("# 落库完成：本次 upsert ${totalRows.get()} 行 → 表内现有 $total 行")
}

private suspend fun fetchTopListWithRetry(yyyymmdd: String, retries: Int): List<TopListRecord>? {
    repeat(retries) { attempt ->
        try {
            val form = tushare.getTopList(tradeDate = yyyymmdd).check() ?: return emptyList()
            val fields = form.fields
            fun idx(name: String) = fields.indexOf(name)
            val iTs = idx("ts_code"); val iName = idx("name"); val iClose = idx("close")
            val iPct = idx("pct_change"); val iTnr = idx("turnover_rate"); val iAmt = idx("amount")
            val iLBuy = idx("l_buy"); val iLSell = idx("l_sell"); val iLAmt = idx("l_amount")
            val iNet = idx("net_amount"); val iNetRate = idx("net_rate"); val iAmtRate = idx("amount_rate")
            val iFloat = idx("float_values"); val iReason = idx("reason")
            // 同股同日多上榜原因 → 取首条（净买入字段一致，按 ts_code 去重）
            val seen = HashSet<String>()
            return form.items.mapNotNull { col ->
                fun s(i: Int) = if (i >= 0) col.getOrNull(i)?.takeIf { !it.isNullOrBlank() && it != "null" } else null
                fun d(i: Int) = if (i >= 0) col.getOrNull(i)?.toDoubleOrNull() else null
                val ts = s(iTs) ?: return@mapNotNull null
                if (!seen.add(ts)) return@mapNotNull null
                TopListRecord(
                    tsCode = ts,
                    tradeDate = yyyymmdd,
                    name = s(iName), close = d(iClose), pctChange = d(iPct), turnoverRate = d(iTnr),
                    amount = d(iAmt), lBuy = d(iLBuy), lSell = d(iLSell), lAmount = d(iLAmt),
                    netAmount = d(iNet), netRate = d(iNetRate), amountRate = d(iAmtRate),
                    floatValues = d(iFloat), reason = s(iReason),
                )
            }
        } catch (e: Exception) {
            if (attempt == retries - 1) {
                println("- $yyyymmdd: 重试 $retries 次失败 error=${e.message}")
            } else {
                delay(500L * (attempt + 1))
            }
        }
    }
    return null
}
