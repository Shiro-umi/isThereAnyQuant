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
import org.shiroumi.database.moneyflow.StockMoneyFlowRecord
import org.shiroumi.database.moneyflow.StockMoneyFlowRepository
import org.shiroumi.network.apis.getStockMoneyFlow
import org.shiroumi.network.apis.tushare

/**
 * 个股日频资金流落库（stock_moneyflow）—— Tushare moneyflow 逐交易日全市场。
 *
 * 设计动机：pivot-crash-stock 缺「主力行为方向」维度——指数下跌日 case2 出货（大单净流出→跟跌）
 * vs case3 拉升（大单净流入→不跌）日 K/换手无法区分，资金流是直接信号。
 *
 * 并发策略对齐项目规范（见 HistoricalDailyBatchSyncService）：
 *   coroutineScope + Semaphore(concurrency) + async(Dispatchers.IO) + awaitAll + 逐日重试。
 *   concurrency 默认 12（moneyflow 是 2000 积分接口，比日线限流严，取保守并发避免触发频控）。
 * 按 (ts_code, trade_date) upsert，可重入续传。
 *
 * 运行：./gradlew :ktor-server:syncStockMoneyFlow -Dquant.mf.start=2020-01-01 -Dquant.mf.end=2026-06-30
 */
fun main() = runBlocking {
    ConfigManager.load()
    val start = System.getProperty("quant.mf.start")?.let { LocalDate.parse(it) } ?: LocalDate.parse("2020-01-01")
    val end = System.getProperty("quant.mf.end")?.let { LocalDate.parse(it) } ?: LocalDate.parse("2026-06-30")
    val concurrency = System.getProperty("quant.mf.concurrency")?.toIntOrNull() ?: 12
    val retries = System.getProperty("quant.mf.retries")?.toIntOrNull() ?: 3
    println("# 个股资金流落库 stock_moneyflow  $start ~ $end  (并发=$concurrency)")

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
                    val records = fetchWithRetry(yyyymmdd, retries) ?: return@withPermit
                    StockMoneyFlowRepository.upsert(records)
                    totalRows.addAndGet(records.size)
                    val done = doneDays.incrementAndGet()
                    if (done % 100 == 0) println("- 进度 $done/${days.size}：累计 ${totalRows.get()} 行")
                }
            }
        }.awaitAll()
    }
    val total = StockMoneyFlowRepository.count()
    println("# 落库完成：本次 upsert ${totalRows.get()} 行 → 表内现有 $total 行")
}

private suspend fun fetchWithRetry(yyyymmdd: String, retries: Int): List<StockMoneyFlowRecord>? {
    repeat(retries) { attempt ->
        try {
            val form = tushare.getStockMoneyFlow(tradeDate = yyyymmdd).check() ?: return emptyList()
            val fields = form.fields
            fun idx(name: String) = fields.indexOf(name)
            val iTs = idx("ts_code"); val iDate = idx("trade_date")
            val iBuyLg = idx("buy_lg_amount"); val iSellLg = idx("sell_lg_amount")
            val iBuyElg = idx("buy_elg_amount"); val iSellElg = idx("sell_elg_amount")
            val iNet = idx("net_mf_amount")
            return form.items.mapNotNull { col ->
                fun d(i: Int) = if (i >= 0) col.getOrNull(i)?.toDoubleOrNull() else null
                fun s(i: Int) = if (i >= 0) col.getOrNull(i)?.takeIf { !it.isNullOrBlank() && it != "null" } else null
                val ts = s(iTs) ?: return@mapNotNull null
                StockMoneyFlowRecord(
                    tsCode = ts,
                    tradeDate = s(iDate) ?: yyyymmdd,
                    buyLgAmount = d(iBuyLg), sellLgAmount = d(iSellLg),
                    buyElgAmount = d(iBuyElg), sellElgAmount = d(iSellElg),
                    netMfAmount = d(iNet),
                )
            }
        } catch (e: Exception) {
            if (attempt == retries - 1) {
                println("- $yyyymmdd: 重试 $retries 次失败 error=${e.message}")
            } else {
                delay(500L * (attempt + 1))   // 退避重试，缓解频控
            }
        }
    }
    return null
}
