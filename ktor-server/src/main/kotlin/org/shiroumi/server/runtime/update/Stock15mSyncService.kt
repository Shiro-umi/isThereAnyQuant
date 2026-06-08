package org.shiroumi.server.runtime.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.repository.StockBasicRepository
import org.shiroumi.database.stock.StockMinute15mRepository
import org.shiroumi.network.apis.getStkMins
import org.shiroumi.network.apis.tushare
import utils.logger
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.random.Random

private val stock15mLogger by logger("Stock15mSyncService")

/**
 * 股票 15min 历史 K 线一次性回填服务。
 *
 * `stk_mins` 是逐 ts_code + 时间窗接口，且要求 start/end 同给、单次窗口行数不超过接口上限。
 * 它不是“交易日 -> 全市场分钟线”接口，因此不能套用 kpl_list/limit_list_d 的按交易日遍历模式。
 * 这里按“股票 -> 年度窗口”遍历：15min 每年约 4k 根，按年窗拉取可控；写入 `stock_minute_15m`，重跑幂等。
 */
class Stock15mSyncService(
    private val concurrency: Int = 30,
    private val requestIntervalMillis: Long = 350L,
    private val inlineRetryAttempts: Int = 3,
    private val fetchTimeoutMillis: Long = 120_000L,
    private val rateLimitCooldownMillis: Long = 120_000L,
    private val maxRateLimitCooldownMillis: Long = 600_000L,
    private val progressEverySymbols: Int = 50,
    private val detailedLog: Boolean = false,
    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai"),
) {
    private val requestRateMutex = Mutex()
    private var nextRequestAtMillis: Long = 0L
    private var rateLimitBlockedUntilMillis: Long = 0L

    suspend fun backfill(
        fromYear: Int = 2000,
        toYear: Int? = null,
        symbolLimit: Int = 0,
        skipExistingWindows: Boolean = true,
        shardIndex: Int = 0,
        shardCount: Int = 1,
    ) {
        require(concurrency >= 1) { "concurrency must be >= 1" }
        require(shardCount >= 1) { "shardCount must be >= 1" }
        require(shardIndex in 0 until shardCount) { "shardIndex must be in [0, shardCount)" }
        val endYear = toYear ?: java.time.LocalDate.now(shanghaiZone).year
        val all = StockBasicRepository.findProfiles().map { profile ->
            SymbolPlan(
                code = profile.tsCode,
                firstYear = profile.listDate?.year?.coerceAtLeast(fromYear) ?: fromYear,
            )
        }
        val sharded = all.filterIndexed { index, _ -> index % shardCount == shardIndex }
        val symbols = if (symbolLimit > 0) sharded.take(symbolLimit) else sharded
        val windows = (fromYear..endYear).map { year ->
            Window(
                year = year,
                startDate = LocalDate(year, 1, 1),
                endDate = LocalDate(year, 12, 31),
                start = "$year-01-01 09:00:00",
                end = "$year-12-31 16:00:00",
            )
        }
        stock15mLogger.info(
            "[stock15m] 全量回填：股票=${symbols.size}/${all.size} shard=$shardIndex/$shardCount 年份=$fromYear..$endYear " +
                "并发=$concurrency requestIntervalMillis=$requestIntervalMillis skipExistingWindows=$skipExistingWindows " +
                "rateLimitCooldownMillis=$rateLimitCooldownMillis maxRateLimitCooldownMillis=$maxRateLimitCooldownMillis " +
                "progressEverySymbols=$progressEverySymbols detailedLog=$detailedLog listDateSkip=true"
        )
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val totalRows = AtomicLong(0L)
        fanOutSymbols(symbols) { plan ->
            var rows = 0
            var skippedWindows = 0
            val startedAt = System.currentTimeMillis()
            if (detailedLog) {
                stock15mLogger.info("[stock15m] ${plan.code} 开始回填 firstYear=${plan.firstYear}")
            }
            for (window in windows.asSequence().filter { it.year >= plan.firstYear }) {
                if (skipExistingWindows &&
                    StockMinute15mRepository.existsForCodeWindow(plan.code, window.startDate, window.endDate)
                ) {
                    skippedWindows += 1
                    if (detailedLog) {
                        stock15mLogger.info("[stock15m] ${plan.code} ${window.year} 已存在，跳过")
                    }
                    continue
                }
                rows += fetchWindow(plan.code, window)
            }
            val currentDone = done.incrementAndGet()
            totalRows.addAndGet(rows.toLong())
            if (detailedLog) {
                val elapsedMs = System.currentTimeMillis() - startedAt
                stock15mLogger.info(
                    "[stock15m] ${plan.code} 完成 rows=$rows skippedWindows=$skippedWindows elapsedMs=$elapsedMs"
                )
            }
            if (progressEverySymbols > 0 && currentDone % progressEverySymbols == 0) {
                stock15mLogger.info(
                    "[stock15m] 回填进度 $currentDone/${symbols.size} rows=${totalRows.get()} failed=${failed.get()}"
                )
            }
            rows
        }.forEach { result ->
            if (result.failed) failed.incrementAndGet()
        }
        stock15mLogger.info("[stock15m] 全量回填完成：股票=${symbols.size} rows=${totalRows.get()} failed=${failed.get()}")
    }

    private suspend fun fanOutSymbols(
        symbols: List<SymbolPlan>,
        perSymbol: suspend (SymbolPlan) -> Int,
    ): List<SymbolResult> = coroutineScope {
        val sem = Semaphore(concurrency)
        symbols.map { plan ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    runCatching { SymbolResult(code = plan.code, rows = perSymbol(plan), failed = false) }
                        .getOrElse { e ->
                            stock15mLogger.warning("[stock15m] ${plan.code} 回填失败: ${e.message}")
                            SymbolResult(code = plan.code, rows = 0, failed = true)
                        }
                }
            }
        }.awaitAll()
    }

    private suspend fun fetchWindow(code: String, window: Window): Int {
        var lastErr: Throwable? = null
        var attempt = 0
        var rateLimitHits = 0
        while (attempt < inlineRetryAttempts) {
            try {
                if (detailedLog) {
                    stock15mLogger.info(
                        "[stock15m] $code ${window.start}~${window.end} 请求开始 attempt=${attempt + 1}/$inlineRetryAttempts"
                    )
                }
                val startedAt = System.currentTimeMillis()
                throttleRequest()
                val form = withTimeout(fetchTimeoutMillis) {
                    tushare.getStkMins(code, "15min", window.start, window.end).check()
                } ?: return 0
                val f = form.fields
                val iTime = f.indexOf("trade_time")
                val iOpen = f.indexOf("open")
                val iHigh = f.indexOf("high")
                val iLow = f.indexOf("low")
                val iClose = f.indexOf("close")
                val iVol = f.indexOf("vol")
                val iAmt = f.indexOf("amount")
                if (listOf(iTime, iOpen, iHigh, iLow, iClose, iVol, iAmt).any { it < 0 }) return 0
                val now = System.currentTimeMillis()
                val rows = form.items.mapNotNull { row ->
                    val tt = row.getOrNull(iTime) ?: return@mapNotNull null
                    val date = runCatching { LocalDate.parse(tt.take(10)) }.getOrNull() ?: return@mapNotNull null
                    StockMinute15mRepository.Minute15mRow(
                        tsCode = code,
                        tradeDate = date,
                        tradeTime = tt,
                        open = row.getOrNull(iOpen)?.toFloatOrNull() ?: 0f,
                        high = row.getOrNull(iHigh)?.toFloatOrNull() ?: 0f,
                        low = row.getOrNull(iLow)?.toFloatOrNull() ?: 0f,
                        close = row.getOrNull(iClose)?.toFloatOrNull() ?: 0f,
                        vol = row.getOrNull(iVol)?.toFloatOrNull() ?: 0f,
                        amount = row.getOrNull(iAmt)?.toFloatOrNull() ?: 0f,
                        updatedAtMillis = now,
                    )
                }
                StockMinute15mRepository.upsertRows(rows)
                if (detailedLog) {
                    val elapsedMs = System.currentTimeMillis() - startedAt
                    stock15mLogger.info(
                        "[stock15m] $code ${window.start}~${window.end} 写入 rows=${rows.size} elapsedMs=$elapsedMs"
                    )
                }
                return rows.size
            } catch (e: Throwable) {
                lastErr = e
                val rateLimited = e.message?.contains("40203") == true || e.message?.contains("频率超限") == true
                if (rateLimited) {
                    rateLimitHits += 1
                    val cooldownMillis = rateLimitCooldownDelay(rateLimitHits)
                    val waitMillis = registerGlobalRateLimitCooldown(cooldownMillis)
                    stock15mLogger.warning(
                        "[stock15m] $code ${window.start}~${window.end} 命中频控，cooldownMs=$cooldownMillis waitMs=$waitMillis " +
                            "rateLimitHits=$rateLimitHits，等待后重试: ${e.message}"
                    )
                    delay(waitMillis)
                    continue
                }
                attempt += 1
                if (attempt < inlineRetryAttempts) delay(1_000L * attempt + Random.nextLong(0, 500))
            }
        }
        stock15mLogger.warning("[stock15m] $code ${window.start}~${window.end} 失败: ${lastErr?.message}")
        return 0
    }

    private fun rateLimitCooldownDelay(rateLimitHits: Int): Long {
        val multiplier = 1L shl (rateLimitHits - 1).coerceIn(0, 3)
        val base = rateLimitCooldownMillis.coerceAtLeast(1_000L)
        val capped = (base * multiplier).coerceAtMost(maxRateLimitCooldownMillis.coerceAtLeast(base))
        return capped + Random.nextLong(0, 5_000)
    }

    private suspend fun registerGlobalRateLimitCooldown(cooldownMillis: Long): Long {
        val now = System.currentTimeMillis()
        val blockedUntil = now + cooldownMillis.coerceAtLeast(1_000L)
        return requestRateMutex.withLock {
            rateLimitBlockedUntilMillis = max(rateLimitBlockedUntilMillis, blockedUntil)
            (rateLimitBlockedUntilMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)
        }
    }

    private suspend fun throttleRequest() {
        requestRateMutex.withLock {
            val now = System.currentTimeMillis()
            val intervalBlockedUntilMillis = if (requestIntervalMillis > 0L) nextRequestAtMillis else 0L
            val waitUntilMillis = max(intervalBlockedUntilMillis, rateLimitBlockedUntilMillis)
            val waitMillis = waitUntilMillis - now
            if (waitMillis > 0L) delay(waitMillis)
            if (requestIntervalMillis > 0L) {
                nextRequestAtMillis = System.currentTimeMillis() + requestIntervalMillis
            }
        }
    }

    private data class Window(
        val year: Int,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val start: String,
        val end: String,
    )

    private data class SymbolPlan(
        val code: String,
        val firstYear: Int,
    )

    private data class SymbolResult(
        val code: String,
        val rows: Int,
        val failed: Boolean,
    )
}
