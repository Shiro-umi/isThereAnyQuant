package org.shiroumi.server.runtime.update

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.compensation.CompensationTaskType
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.LimitListDRecord
import org.shiroumi.database.stock.LimitListDRepository
import org.shiroumi.server.dataprovider.port.RemoteLimitListDFetcher
import utils.logger
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

private val limitListLogger by logger("LimitListDSyncService")
private val LIMIT_LIST_D_EARLIEST_TRADE_DATE = LocalDate(2020, 1, 1)

interface LimitListDSyncService {
    suspend fun syncPendingTradeDates(): LimitListDSyncResult

    suspend fun syncTradeDates(tradeDates: List<LocalDate>): LimitListDSyncResult {
        if (tradeDates.isNotEmpty()) {
            throw UnsupportedOperationException("当前实现不支持按指定交易日同步涨跌停炸板数据")
        }
        return LimitListDSyncResult()
    }
}

data class LimitListDSyncResult(
    val completedDates: List<LocalDate> = emptyList(),
    val enqueuedDates: List<LocalDate> = emptyList(),
)

class DefaultLimitListDSyncService(
    private val remoteLimitListDFetcher: RemoteLimitListDFetcher,
    private val compensationTaskService: DataCompensationTaskService? = null,
    private val shanghaiClock: Clock = Clock.system(ZoneId.of("Asia/Shanghai")),
    private val inlineRetryAttempts: Int = 3,
    private val fetchTimeoutMillis: Long = 120_000L,
    private val pendingDateLoader: (LocalDate) -> List<LocalDate> = {
        TradingCalendarRepository.findPendingLimitListDDates(it)
    },
    private val limitListPersister: (LocalDate, List<LimitListDRecord>) -> Unit = { tradeDate, records ->
        LimitListDRepository.replaceForTradeDate(tradeDate, records)
    },
    private val completedDateMarker: (List<LocalDate>) -> Unit = {
        TradingCalendarRepository.markLimitListDUpdated(it)
    }
) : LimitListDSyncService {
    private val sameDaySyncCutoff: LocalTime = LocalTime.of(16, 30)

    private fun maxEligibleTradeDate(): LocalDate? {
        val now = java.time.LocalDateTime.now(shanghaiClock)
        val today = LocalDate(now.year, now.monthValue, now.dayOfMonth)
        return if (now.toLocalTime() < sameDaySyncCutoff) {
            TradingCalendarRepository.findPreviousTradingDate(today)
        } else {
            TradingCalendarRepository.findLatestTradingDateOnOrBefore(today)
        }
    }

    override suspend fun syncPendingTradeDates(): LimitListDSyncResult {
        val maxTradeDate = maxEligibleTradeDate()
        if (maxTradeDate == null) {
            limitListLogger.info("未找到可同步的交易日上界，跳过涨跌停炸板数据同步。")
            return LimitListDSyncResult()
        }
        return syncTradeDates(pendingDateLoader(maxTradeDate))
    }

    override suspend fun syncTradeDates(tradeDates: List<LocalDate>): LimitListDSyncResult {
        val maxTradeDate = maxEligibleTradeDate()
        val pendingDates = tradeDates.distinct().sorted().filter { tradeDate ->
            tradeDate >= LIMIT_LIST_D_EARLIEST_TRADE_DATE && (maxTradeDate == null || tradeDate <= maxTradeDate)
        }
        if (pendingDates.isEmpty()) {
            limitListLogger.info("没有待同步的涨跌停炸板交易日。")
            return LimitListDSyncResult()
        }

        val completed = mutableListOf<LocalDate>()
        val enqueued = mutableListOf<LocalDate>()
        pendingDates.forEach { tradeDate ->
            when (val outcome = fetchTradeDateWithRetry(tradeDate)) {
                is LimitListDFetchOutcome.Success -> {
                    limitListPersister(tradeDate, outcome.records)
                    completedDateMarker(listOf(tradeDate))
                    completed += tradeDate
                    limitListLogger.info(
                        "[涨跌停炸板同步] COMPLETED | tradeDate=$tradeDate, rows=${outcome.records.size}"
                    )
                }
                is LimitListDFetchOutcome.Failure -> {
                    val errorMessage = "交易日 $tradeDate 涨跌停炸板数据同步失败: ${outcome.errorMessage}"
                    if (compensationTaskService != null) {
                        compensationTaskService.enqueueFailure(
                            taskType = CompensationTaskType.LIMIT_LIST_D_BY_TRADE_DATE,
                            tradeDate = tradeDate,
                            sourceStage = "LIMIT_LIST_D_SYNC_SERVICE",
                            lastError = errorMessage,
                        )
                    } else {
                        limitListLogger.error(
                            "[涨跌停炸板同步] FAILED_WITHOUT_COMPENSATION | tradeDate=$tradeDate, error=$errorMessage"
                        )
                    }
                    enqueued += tradeDate
                }
            }
        }

        return LimitListDSyncResult(
            completedDates = completed.sorted(),
            enqueuedDates = enqueued.sorted(),
        )
    }

    private suspend fun fetchTradeDateWithRetry(tradeDate: LocalDate): LimitListDFetchOutcome {
        var lastError: Throwable? = null
        repeat(inlineRetryAttempts) { attempt ->
            try {
                val records = withTimeout(fetchTimeoutMillis) {
                    remoteLimitListDFetcher.fetch(tradeDate)
                }
                if (attempt > 0) {
                    limitListLogger.info(
                        "[涨跌停炸板同步] RECOVERED_AFTER_INLINE_RETRY | " +
                            "tradeDate=$tradeDate, attempt=${attempt + 1}/$inlineRetryAttempts"
                    )
                }
                return LimitListDFetchOutcome.Success(records)
            } catch (error: Throwable) {
                lastError = error
                if (attempt < inlineRetryAttempts - 1) {
                    limitListLogger.warning(
                        "[涨跌停炸板同步] RETRY_INLINE | tradeDate=$tradeDate, " +
                            "attempt=${attempt + 1}/$inlineRetryAttempts, error=${error.message}"
                    )
                    delay(retryDelayMillis(attempt))
                }
            }
        }
        return LimitListDFetchOutcome.Failure(lastError?.message ?: "unknown")
    }

    private fun retryDelayMillis(attempt: Int): Long {
        val base = when (attempt) {
            0 -> 1_000L
            1 -> 3_000L
            else -> 10_000L
        }
        return base + Random.nextLong(0L, 500L)
    }

    private sealed interface LimitListDFetchOutcome {
        data class Success(val records: List<LimitListDRecord>) : LimitListDFetchOutcome
        data class Failure(val errorMessage: String) : LimitListDFetchOutcome
    }
}
