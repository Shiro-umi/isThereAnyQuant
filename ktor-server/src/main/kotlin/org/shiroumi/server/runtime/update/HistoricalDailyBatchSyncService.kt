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
import model.Candle
import model.dataprovider.HistoricalDailyBatchRequest
import org.shiroumi.database.common.compensation.CompensationTaskType
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.server.dataprovider.port.RemoteHistoricalDailyBatchFetcher
import utils.logger
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

private val logger by logger("HistoricalDailyBatchSyncService")

interface HistoricalDailyBatchSyncService {
    suspend fun syncPendingTradeDates(): HistoricalDailyBatchSyncResult

    suspend fun syncTradeDates(tradeDates: List<LocalDate>): HistoricalDailyBatchSyncResult {
        if (tradeDates.isNotEmpty()) {
            throw UnsupportedOperationException("当前实现不支持按指定交易日同步")
        }
        return HistoricalDailyBatchSyncResult()
    }
}

data class HistoricalDailyBatchSyncResult(
    val completedDates: List<LocalDate> = emptyList(),
    val enqueuedDates: List<LocalDate> = emptyList(),
)

class DefaultHistoricalDailyBatchSyncService(
    private val remoteHistoricalDailyBatchFetcher: RemoteHistoricalDailyBatchFetcher,
    private val compensationTaskService: DataCompensationTaskService? = null,
    private val shanghaiClock: Clock = Clock.system(ZoneId.of("Asia/Shanghai")),
    val concurrency: Int = 450,
    val chunkSize: Int = 100,
    private val inlineRetryAttempts: Int = 3,
    private val pendingDateLoader: (LocalDate) -> List<LocalDate> = {
        TradingCalendarRepository.findPendingStockDailyDates(it)
    },
    private val rawDailyFactPersister: (List<Candle>) -> Unit = {
        StockDailyCandleRepository.replaceRawDailyFacts(it)
    },
    private val completedDateMarker: (List<LocalDate>) -> Unit = {
        TradingCalendarRepository.markStockDailyUpdated(it)
    }
) : HistoricalDailyBatchSyncService {
    private val sameDaySyncCutoff: LocalTime = LocalTime.of(16, 30)
    private val maxFetchRounds: Int = 2
    private val fetchTimeoutMillis: Long = 120_000L

    private fun maxEligibleTradeDate(): LocalDate? {
        val now = java.time.LocalDateTime.now(shanghaiClock)
        val today = LocalDate(now.year, now.monthValue, now.dayOfMonth)
        return if (now.toLocalTime() < sameDaySyncCutoff) {
            TradingCalendarRepository.findPreviousTradingDate(today)
        } else {
            TradingCalendarRepository.findLatestTradingDateOnOrBefore(today)
        }
    }

    override suspend fun syncPendingTradeDates(): HistoricalDailyBatchSyncResult {
        val maxTradeDate = maxEligibleTradeDate()
        if (maxTradeDate == null) {
            logger.info("未找到可同步的交易日上界，跳过日线同步。")
            return HistoricalDailyBatchSyncResult()
        }
        return syncTradeDates(pendingDateLoader(maxTradeDate))
    }

    override suspend fun syncTradeDates(tradeDates: List<LocalDate>): HistoricalDailyBatchSyncResult {
        val maxTradeDate = maxEligibleTradeDate()
        val pendingDates = tradeDates.distinct().sorted().filter { tradeDate ->
            maxTradeDate == null || tradeDate <= maxTradeDate
        }
        if (pendingDates.isEmpty()) {
            logger.info("没有待同步的日线交易日。")
            return HistoricalDailyBatchSyncResult()
        }

        val recoveredSuccesses = linkedMapOf<LocalDate, List<Candle>>()
        var remaining = pendingDates
        var round = 1

        while (remaining.isNotEmpty() && round <= maxFetchRounds) {
            val roundLabel = if (round == 1) "FIRST_PASS" else "TAIL_RETRY"
            logger.info("[日线事实同步] $roundLabel | 待同步 ${remaining.size} 个交易日")

            val result = executeFetchRound(remaining, round = roundLabel)

            // 收集本轮成功结果，尾部重试结束后统一落库并标记 calendar 完成
            if (result.successes.isNotEmpty()) {
                val successMap = result.successes
                    .sortedBy { it.tradeDate }
                    .associate { it.tradeDate to it.candles }
                recoveredSuccesses.putAll(successMap)
            }

            val failedDates = result.failures.map { it.tradeDate }
            if (failedDates.isEmpty()) {
                break
            }

            remaining = failedDates
            if (round < maxFetchRounds) {
                failedDates.forEach { tradeDate ->
                    logger.warning("[日线事实同步] REQUEUED_TO_TAIL | tradeDate=$tradeDate")
                }
                delay(5_000L)
            }
            round++
        }

        if (recoveredSuccesses.isNotEmpty()) {
            persistSuccesses(recoveredSuccesses)
        }

        val allCompleted = recoveredSuccesses.keys.toList()
        val stillFailed = remaining.filter { it !in allCompleted }
        if (stillFailed.isNotEmpty()) {
            stillFailed.forEach { tradeDate ->
                val errorMessage = "交易日 $tradeDate 日线事实在首轮与尾部重试后仍失败"
                if (compensationTaskService != null) {
                    compensationTaskService.enqueueFailure(
                        taskType = CompensationTaskType.DAILY_FACT_BY_TRADE_DATE,
                        tradeDate = tradeDate,
                        sourceStage = "HISTORICAL_DAILY_BATCH_SYNC_SERVICE",
                        lastError = errorMessage,
                    )
                } else {
                    logger.error("[日线事实同步] FAILED_WITHOUT_COMPENSATION | tradeDate=$tradeDate, error=$errorMessage")
                }
            }
        }

        return HistoricalDailyBatchSyncResult(
            completedDates = allCompleted.sorted(),
            enqueuedDates = stillFailed.sorted(),
        )
    }

    private suspend fun executeFetchRound(
        tradeDates: List<LocalDate>,
        round: String,
    ): FetchRoundResult = coroutineScope {
        if (tradeDates.isEmpty()) return@coroutineScope FetchRoundResult()

        val semaphore = Semaphore(concurrency)
        val results: List<Pair<LocalDate, TradeDateFetchOutcome>> = tradeDates.map { tradeDate ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    tradeDate to fetchTradeDateWithRetry(tradeDate)
                }
            }
        }.awaitAll()

        val successes = mutableListOf<TradeDateFetchSuccess>()
        val failures = mutableListOf<TradeDateFetchFailure>()
        results.forEach { (_, outcome) ->
            when (outcome) {
                is TradeDateFetchOutcome.Success -> {
                    successes += outcome.success
                    if (round == "TAIL_RETRY") {
                        logger.info(
                            "[日线事实同步] RECOVERED_AFTER_TAIL_RETRY | " +
                                "tradeDate=${outcome.success.tradeDate}, attempts=${outcome.success.attempts}"
                        )
                    }
                }
                is TradeDateFetchOutcome.Failure -> {
                    failures += outcome.failure
                    logger.error(
                        "[日线事实同步] ${if (round == "FIRST_PASS") "FAILED_FIRST_PASS" else "FAILED_TAIL_PASS"} | " +
                            "tradeDate=${outcome.failure.tradeDate}, error=${outcome.failure.errorMessage}"
                    )
                }
            }
        }
        FetchRoundResult(successes = successes, failures = failures)
    }

    private suspend fun fetchTradeDateWithRetry(tradeDate: LocalDate): TradeDateFetchOutcome {
        var lastError: Throwable? = null
        repeat(inlineRetryAttempts) { attempt ->
            try {
                val candles = withTimeout(fetchTimeoutMillis) {
                    remoteHistoricalDailyBatchFetcher.fetch(
                        HistoricalDailyBatchRequest(tradeDate = tradeDate)
                    )
                }
                require(candles.isNotEmpty()) {
                    "开市日 $tradeDate 没有抓取到任何日线事实，拒绝推进 calendar 状态"
                }
                if (attempt > 0) {
                    logger.info(
                        "[日线事实同步] RECOVERED_AFTER_INLINE_RETRY | " +
                            "tradeDate=$tradeDate, attempt=${attempt + 1}/$inlineRetryAttempts"
                    )
                }
                return TradeDateFetchOutcome.Success(
                    TradeDateFetchSuccess(
                        tradeDate = tradeDate,
                        candles = candles,
                        attempts = attempt + 1,
                    )
                )
            } catch (error: Throwable) {
                lastError = error
                if (attempt < inlineRetryAttempts - 1) {
                    logger.warning(
                        "[日线事实同步] RETRY_INLINE | tradeDate=$tradeDate, attempt=${attempt + 1}/$inlineRetryAttempts, error=${error.message}"
                    )
                    delay(retryDelayMillis(attempt))
                }
            }
        }
        return TradeDateFetchOutcome.Failure(
            TradeDateFetchFailure(
                tradeDate = tradeDate,
                errorMessage = lastError?.message ?: "unknown",
            )
        )
    }

    private fun persistSuccesses(successes: Map<LocalDate, List<Candle>>) {
        if (successes.isEmpty()) return
        successes.entries.toList().chunked(chunkSize).forEach { chunk ->
            val allCandles = chunk.flatMap { it.value }
            rawDailyFactPersister(allCandles)
            val completedDates = chunk.map { it.key }
            completedDateMarker(completedDates)
            logger.info("已完成日线事实同步批次: $completedDates")
        }
    }

    private fun retryDelayMillis(attempt: Int): Long {
        val base = when (attempt) {
            0 -> 1_000L
            1 -> 3_000L
            else -> 10_000L
        }
        return base + Random.nextLong(0L, 500L)
    }

    private data class FetchRoundResult(
        val successes: List<TradeDateFetchSuccess> = emptyList(),
        val failures: List<TradeDateFetchFailure> = emptyList(),
    )

    private data class TradeDateFetchSuccess(
        val tradeDate: LocalDate,
        val candles: List<Candle>,
        val attempts: Int,
    )

    private data class TradeDateFetchFailure(
        val tradeDate: LocalDate,
        val errorMessage: String,
    )

    private sealed interface TradeDateFetchOutcome {
        data class Success(val success: TradeDateFetchSuccess) : TradeDateFetchOutcome
        data class Failure(val failure: TradeDateFetchFailure) : TradeDateFetchOutcome
    }


}
