package org.shiroumi.server.runtime.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.repository.StockBasicRepository
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockOpen5mRepository
import org.shiroumi.network.apis.getStkMins
import org.shiroumi.network.apis.tushare
import utils.logger
import java.time.ZoneId
import kotlin.random.Random

private val logger by logger("Open5mSyncService")

/**
 * 每日首根 5min K 线的**并发**采集服务（接入盘后主链路）。
 *
 * 与日线 `daily`（一次取全市场某日）不同，Tushare `stk_mins` 是**逐 ts_code + 时间窗**接口，
 * 故并发模型是**按股票 fan-out**（Semaphore 限并发），每股拉自身窗口、每 trade_date 取首根（含 09:30）upsert。
 * 数据源 stk_mins 自 2010 起；硬约束：start/end 同给且区间 ≤8000 条（5min×半年≈5760 行 < 8000）。
 *
 * 两种模式：
 * - **盘后增量**（[syncPendingDates]）：只补「日线已就绪但 open5m 缺口」的交易日——逐缺口日对全 symbol 拉当日单根。
 * - **全量回填**（[backfill]）：symbol × 半年窗，幂等 upsert，可断点续跑。
 *
 * 幂等：`StockOpen5mRepository.upsertRows` 基于 uk_open5m_code_date 原地覆盖，重跑安全（天然支持盘后追平）。
 */
class Open5mSyncService(
    private val concurrency: Int = 50,          // stk_mins 默认 400/min，按股 fan-out 取保守并发避免限速堆积
    private val inlineRetryAttempts: Int = 3,
    private val fetchTimeoutMillis: Long = 120_000L,
    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai"),
) {

    /**
     * 盘后增量：补近期 open5m 尚缺的交易日（全 symbol 拉这些日的首根）。
     *
     * 约束：只补最近 [lookbackDays] 个交易日内的缺口，避免远古缺口（如 2010 年）
     * 阻塞盘后主链路。远古缺口由独立全量回填任务处理。
     */
    suspend fun syncPendingDates(lookbackDays: Int = 30) {
        val today = java.time.LocalDate.now(shanghaiZone)
        val maxDate = TradingCalendarRepository.findLatestTradingDateOnOrBefore(
            LocalDate(today.year, today.monthValue, today.dayOfMonth)
        ) ?: return
        // 计算 lookback 起始日期：从 maxDate 往前数 lookbackDays 个开盘日
        val allOpenDates = TradingCalendarRepository.findOpenDates(
            LocalDate(2010, 1, 1), maxDate
        )
        val fromInclusive = allOpenDates.takeLast(lookbackDays).firstOrNull() ?: return
        val openDates = TradingCalendarRepository.findOpenDates(fromInclusive, maxDate)
        val existing = StockOpen5mRepository.findExistingTradeDates(fromInclusive, maxDate)
        val missing = openDates.filter { it !in existing }
        if (missing.isEmpty()) { logger.info("[open5m] 无缺口交易日，盘后追平跳过。"); return }
        logger.info("[open5m] 盘后追平：${missing.size} 个缺口交易日 [${missing.first()}..${missing.last()}] (lookback=${lookbackDays}d)")
        // 缺口通常很少（盘后只差当日）；逐缺口日对全 symbol 并发拉单根。
        val symbols = StockBasicRepository.getActiveSymbols()
        for (date in missing) {
            val ds = date.toString()
            fanOutSymbols(symbols) { code -> fetchWindow(code, "$ds 09:00:00", "$ds 16:00:00") }
            logger.info("[open5m] 追平完成 $date")
        }
    }

    /** 全量回填：[fromYear, toYear] 每股按半年窗拉取首根，幂等 upsert。 */
    suspend fun backfill(fromYear: Int = 2010, toYear: Int? = null, symbolLimit: Int = 0) {
        val endYear = toYear ?: java.time.LocalDate.now(shanghaiZone).year
        val all = StockBasicRepository.getActiveSymbols()
        val symbols = if (symbolLimit > 0) all.take(symbolLimit) else all
        logger.info("[open5m] 全量回填：股票=${symbols.size}/${all.size} 年份=$fromYear..$endYear 并发=$concurrency")
        val windows = buildList {
            for (y in fromYear..endYear) {
                add("$y-01-01 09:00:00" to "$y-06-30 16:00:00")
                add("$y-07-01 09:00:00" to "$y-12-31 16:00:00")
            }
        }
        var done = 0
        fanOutSymbols(symbols) { code ->
            var rows = 0
            for ((s, e) in windows) rows += fetchWindow(code, s, e)
            synchronized(this) { done++; if (done % 100 == 0) logger.info("[open5m] 回填进度 $done/${symbols.size}") }
            rows
        }
        logger.info("[open5m] 全量回填完成：股票=${symbols.size}")
    }

    /** 按股 fan-out 并发执行 [perSymbol]，Semaphore 限并发。 */
    private suspend fun fanOutSymbols(symbols: List<String>, perSymbol: suspend (String) -> Int) = coroutineScope {
        val sem = Semaphore(concurrency)
        symbols.map { code ->
            async(Dispatchers.IO) { sem.withPermit { runCatching { perSymbol(code) }.getOrElse { 0 } } }
        }.awaitAll()
    }

    /** 拉某股票某窗的全天 5min bar，每 trade_date 取首根 upsert，返回写入行数。带内联重试。 */
    private suspend fun fetchWindow(code: String, start: String, end: String): Int {
        var lastErr: Throwable? = null
        repeat(inlineRetryAttempts) { attempt ->
            try {
                val form = withTimeout(fetchTimeoutMillis) { tushare.getStkMins(code, "5min", start, end).check() }
                    ?: return 0
                val f = form.fields
                val iTime = f.indexOf("trade_time")
                if (iTime < 0) return 0
                val iOpen = f.indexOf("open"); val iHigh = f.indexOf("high"); val iLow = f.indexOf("low")
                val iClose = f.indexOf("close"); val iVol = f.indexOf("vol"); val iAmt = f.indexOf("amount")
                val firstByDate = HashMap<String, List<String?>>()
                for (row in form.items) {
                    val tt = row.getOrNull(iTime) ?: continue
                    val d = tt.take(10)
                    val cur = firstByDate[d]
                    if (cur == null || tt < (cur.getOrNull(iTime) ?: "")) firstByDate[d] = row
                }
                if (firstByDate.isEmpty()) return 0
                val rows = firstByDate.values.mapNotNull { row ->
                    val tt = row.getOrNull(iTime) ?: return@mapNotNull null
                    StockOpen5mRepository.Open5mRow(
                        tsCode = code, tradeDate = LocalDate.parse(tt.take(10)), tradeTime = tt,
                        open = row.getOrNull(iOpen)?.toFloatOrNull() ?: 0f,
                        high = row.getOrNull(iHigh)?.toFloatOrNull() ?: 0f,
                        low = row.getOrNull(iLow)?.toFloatOrNull() ?: 0f,
                        close = row.getOrNull(iClose)?.toFloatOrNull() ?: 0f,
                        vol = row.getOrNull(iVol)?.toFloatOrNull() ?: 0f,
                        amount = row.getOrNull(iAmt)?.toFloatOrNull() ?: 0f,
                    )
                }
                StockOpen5mRepository.upsertRows(rows)
                return rows.size
            } catch (e: Throwable) {
                lastErr = e
                if (attempt < inlineRetryAttempts - 1) delay(1_000L * (attempt + 1) + Random.nextLong(0, 500))
            }
        }
        logger.warning("[open5m] $code $start~$end 失败: ${lastErr?.message}")
        return 0
    }
}
