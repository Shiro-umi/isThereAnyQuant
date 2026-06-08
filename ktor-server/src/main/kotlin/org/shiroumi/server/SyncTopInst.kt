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
import org.shiroumi.database.stock.TopInstRecord
import org.shiroumi.database.stock.TopInstRepository
import org.shiroumi.network.apis.getTopInst
import org.shiroumi.network.apis.tushare

/**
 * 龙虎榜营业部明细落库（top_inst）—— Tushare top_inst 逐交易日全市场上榜股营业部成交。
 *
 * 设计动机：席位维度（exalter 营业部名称原文入库，散户/机构分类在 pytorch 装配层做）。
 *
 * 并发策略对齐项目规范（见 SyncKplList）：Semaphore(concurrency) + async(IO) + 逐日重试，默认并发 12。
 * 每日数千~万条（每股最多 10 条：买卖各前 5 营业部），按 (ts_code, trade_date, exalter, side) upsert。
 * 实测 2011-2012 起每日有数据，早于此返回空（正常）。
 *
 * 运行：./gradlew :ktor-server:syncTopInst -Dquant.topinst.start=2011-01-01 -Dquant.topinst.end=2026-06-30
 */
fun main() = runBlocking {
    ConfigManager.load()
    val start = System.getProperty("quant.topinst.start")?.let { LocalDate.parse(it) } ?: LocalDate.parse("2011-01-01")
    val end = System.getProperty("quant.topinst.end")?.let { LocalDate.parse(it) } ?: LocalDate.parse("2026-06-30")
    val concurrency = System.getProperty("quant.topinst.concurrency")?.toIntOrNull() ?: 12
    val retries = System.getProperty("quant.topinst.retries")?.toIntOrNull() ?: 3
    println("# 龙虎榜营业部明细落库 top_inst  $start ~ $end  (并发=$concurrency)")

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
                    val records = fetchTopInstWithRetry(yyyymmdd, retries) ?: return@withPermit
                    TopInstRepository.upsert(records)
                    totalRows.addAndGet(records.size)
                    val done = doneDays.incrementAndGet()
                    if (done % 200 == 0) println("- 进度 $done/${days.size}：累计 ${totalRows.get()} 行")
                }
            }
        }.awaitAll()
    }
    val total = TopInstRepository.count()
    println("# 落库完成：本次 upsert ${totalRows.get()} 行 → 表内现有 $total 行")
}

private suspend fun fetchTopInstWithRetry(yyyymmdd: String, retries: Int): List<TopInstRecord>? {
    repeat(retries) { attempt ->
        try {
            val form = tushare.getTopInst(tradeDate = yyyymmdd).check() ?: return emptyList()
            val fields = form.fields
            fun idx(name: String) = fields.indexOf(name)
            val iTs = idx("ts_code"); val iAlter = idx("exalter"); val iSide = idx("side")
            val iBuy = idx("buy"); val iBuyR = idx("buy_rate"); val iSell = idx("sell")
            val iSellR = idx("sell_rate"); val iNet = idx("net_buy"); val iReason = idx("reason")
            // 去重 (ts_code, exalter, side)：同股同日同营业部同方向唯一
            val seen = HashSet<String>()
            return form.items.mapNotNull { col ->
                fun s(i: Int) = if (i >= 0) col.getOrNull(i)?.takeIf { !it.isNullOrBlank() && it != "null" } else null
                fun d(i: Int) = if (i >= 0) col.getOrNull(i)?.toDoubleOrNull() else null
                val ts = s(iTs) ?: return@mapNotNull null
                val alter = s(iAlter) ?: return@mapNotNull null
                val side = s(iSide) ?: return@mapNotNull null
                if (!seen.add("$ts|$alter|$side")) return@mapNotNull null
                TopInstRecord(
                    tsCode = ts,
                    tradeDate = yyyymmdd,
                    exalter = alter,
                    side = side,
                    buy = d(iBuy), buyRate = d(iBuyR), sell = d(iSell), sellRate = d(iSellR),
                    netBuy = d(iNet), reason = s(iReason),
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
