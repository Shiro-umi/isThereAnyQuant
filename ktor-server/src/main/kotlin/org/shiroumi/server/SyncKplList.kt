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
import org.shiroumi.database.kpl.KplListRecord
import org.shiroumi.database.kpl.KplListRepository
import org.shiroumi.network.apis.getKplList
import org.shiroumi.network.apis.tushare

/**
 * 开盘啦榜单落库（kpl_list）—— Tushare kpl_list 逐交易日全市场涨停股 + 题材。
 *
 * 设计动机：题材群体维度（个股所在题材的集体状态，正交于个股量价）。
 *
 * 并发策略对齐项目规范（见 HistoricalDailyBatchSyncService）：
 *   coroutineScope + Semaphore(concurrency) + async(Dispatchers.IO) + awaitAll + 逐日重试。
 *   concurrency 默认 12（kpl_list 2000 积分接口，保守并发避免频控）。
 * 每日仅涨停股（数十~百条），按 (ts_code, trade_date) upsert，可重入续传。
 *
 * 运行：./gradlew :ktor-server:syncKplList -Dquant.kpl.start=2019-01-01 -Dquant.kpl.end=2026-06-30
 */
fun main() = runBlocking {
    ConfigManager.load()
    val start = System.getProperty("quant.kpl.start")?.let { LocalDate.parse(it) } ?: LocalDate.parse("2019-01-01")
    val end = System.getProperty("quant.kpl.end")?.let { LocalDate.parse(it) } ?: LocalDate.parse("2026-06-30")
    val concurrency = System.getProperty("quant.kpl.concurrency")?.toIntOrNull() ?: 12
    val retries = System.getProperty("quant.kpl.retries")?.toIntOrNull() ?: 3
    println("# 开盘啦榜单落库 kpl_list  $start ~ $end  (并发=$concurrency)")

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
                    val records = fetchKplWithRetry(yyyymmdd, retries) ?: return@withPermit
                    KplListRepository.upsert(records)
                    totalRows.addAndGet(records.size)
                    val done = doneDays.incrementAndGet()
                    if (done % 200 == 0) println("- 进度 $done/${days.size}：累计 ${totalRows.get()} 行")
                }
            }
        }.awaitAll()
    }
    val total = KplListRepository.count()
    println("# 落库完成：本次 upsert ${totalRows.get()} 行 → 表内现有 $total 行")
}

private suspend fun fetchKplWithRetry(yyyymmdd: String, retries: Int): List<KplListRecord>? {
    repeat(retries) { attempt ->
        try {
            val form = tushare.getKplList(tradeDate = yyyymmdd).check() ?: return emptyList()
            val fields = form.fields
            fun idx(name: String) = fields.indexOf(name)
            val iTs = idx("ts_code"); val iName = idx("name"); val iTheme = idx("theme")
            val iStatus = idx("status"); val iTag = idx("tag"); val iLu = idx("lu_desc"); val iTr = idx("turnover_rate")
            return form.items.mapNotNull { col ->
                fun s(i: Int) = if (i >= 0) col.getOrNull(i)?.takeIf { !it.isNullOrBlank() && it != "null" } else null
                val ts = s(iTs) ?: return@mapNotNull null
                KplListRecord(
                    tsCode = ts,
                    tradeDate = yyyymmdd,
                    name = s(iName), theme = s(iTheme), status = s(iStatus),
                    tag = s(iTag), luDesc = s(iLu),
                    turnoverRate = if (iTr >= 0) col.getOrNull(iTr)?.toDoubleOrNull() else null,
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
