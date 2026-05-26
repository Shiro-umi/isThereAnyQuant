package org.shiroumi.server.runtime.update

import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockDailyCandleRepository
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId

private val SHANGHAI_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")
private val BACKFILL_FLOOR_DATE = LocalDate(2000, 1, 1)
private val SAME_DAY_SYNC_CUTOFF: LocalTime = LocalTime.of(16, 30)

enum class HistoricalBackfillMode {
    AUTO,
    RANGE;

    companion object {
        fun parse(raw: String?): HistoricalBackfillMode {
            return when (raw?.trim()?.uppercase()) {
                "RANGE" -> RANGE
                else -> AUTO
            }
        }
    }
}

data class HistoricalBackfillOptions(
    val mode: HistoricalBackfillMode,
    val fromDate: LocalDate?,
    val toDate: LocalDate?,
    val resetFlags: Boolean,
) {
    companion object {
        fun fromSystemProperties(): HistoricalBackfillOptions {
            val mode = HistoricalBackfillMode.parse(System.getProperty("quant.backfill.mode"))
            val rawResetFlags = System.getProperty("quant.backfill.resetFlags")?.trim()
            return HistoricalBackfillOptions(
                mode = mode,
                fromDate = System.getProperty("quant.backfill.from")?.trim()?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                toDate = System.getProperty("quant.backfill.to")?.trim()?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                resetFlags = rawResetFlags?.toBooleanStrictOrNull() ?: (mode == HistoricalBackfillMode.RANGE),
            )
        }
    }
}

data class HistoricalBackfillStage(
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val tradeDates: List<LocalDate>,
    val lastContiguousDate: LocalDate?,
) {
    val isEmpty: Boolean get() = tradeDates.isEmpty()
}

data class HistoricalBackfillPlan(
    val mode: HistoricalBackfillMode,
    val resetFlags: Boolean,
    val daily: HistoricalBackfillStage,
    val fq: HistoricalBackfillStage,
    val strategy: HistoricalBackfillStage,
)

class HistoricalBackfillRangeResolver(
    private val clock: Clock = Clock.system(SHANGHAI_ZONE_ID),
    private val latestTradingDateOnOrBefore: (LocalDate) -> LocalDate? = {
        TradingCalendarRepository.findLatestTradingDateOnOrBefore(it)
    },
    private val findOpenDates: (LocalDate, LocalDate) -> List<LocalDate> = { from, to ->
        TradingCalendarRepository.findOpenDates(from, to)
    },
    private val findNextTradingDate: (LocalDate) -> LocalDate? = {
        TradingCalendarRepository.findNextTradingDate(it)
    },
    private val findExistingTradeDates: (LocalDate, LocalDate) -> Set<LocalDate> = { from, to ->
        StockDailyCandleRepository.findExistingTradeDates(from, to)
    },
    private val findDailyUpdatedTradeDates: (LocalDate, LocalDate) -> Set<LocalDate> = { from, to ->
        TradingCalendarRepository.findStockDailyUpdatedOpenDates(from, to)
    },
    private val findFqUpdatedTradeDates: (LocalDate, LocalDate) -> Set<LocalDate> = { from, to ->
        TradingCalendarRepository.findStockDailyFqUpdatedOpenDates(from, to)
    },
    private val findStrategyUpdatedTradeDates: (LocalDate, LocalDate) -> Set<LocalDate> = { from, to ->
        TradingCalendarRepository.findStrategyUpdatedOpenDates(from, to)
    },
) {
    fun resolve(options: HistoricalBackfillOptions): HistoricalBackfillPlan? {
        val now = java.time.LocalDateTime.now(clock)
        val todayLocalDate = LocalDate(now.year, now.monthValue, now.dayOfMonth)
        val syncUpperBound = if (now.toLocalTime() < SAME_DAY_SYNC_CUTOFF) {
            TradingCalendarRepository.findPreviousTradingDate(todayLocalDate)
        } else {
            latestTradingDateOnOrBefore(todayLocalDate)
        } ?: return null
        val requestedEnd = options.toDate ?: todayLocalDate
        val resolvedEnd = latestTradingDateOnOrBefore(minOf(requestedEnd, syncUpperBound)) ?: return null

        val daily = resolveDailyStage(options, resolvedEnd)
        val fq = resolveFqStage(options, resolvedEnd)
        val strategy = resolveStrategyStage(options, resolvedEnd)

        if (daily.isEmpty && fq.isEmpty && strategy.isEmpty) {
            return null
        }

        return HistoricalBackfillPlan(
            mode = options.mode,
            resetFlags = options.resetFlags,
            daily = daily,
            fq = fq,
            strategy = strategy,
        )
    }

    private fun resolveDailyStage(options: HistoricalBackfillOptions, resolvedEnd: LocalDate): HistoricalBackfillStage {
        val lastContiguousDate = findLastContiguousDailyDate(resolvedEnd)
        val requestedStart = when {
            options.fromDate != null -> maxOf(options.fromDate, BACKFILL_FLOOR_DATE)
            lastContiguousDate != null -> findNextTradingDate(lastContiguousDate)
            else -> BACKFILL_FLOOR_DATE
        }
        return buildStage(requestedStart, resolvedEnd, lastContiguousDate)
    }

    private fun resolveFqStage(options: HistoricalBackfillOptions, resolvedEnd: LocalDate): HistoricalBackfillStage {
        val lastContiguousDate = findLastContiguousFqDate(resolvedEnd)
        val requestedStart = when {
            options.fromDate != null -> maxOf(options.fromDate, BACKFILL_FLOOR_DATE)
            lastContiguousDate != null -> findNextTradingDate(lastContiguousDate)
            else -> BACKFILL_FLOOR_DATE
        }
        return buildStage(requestedStart, resolvedEnd, lastContiguousDate)
    }

    private fun resolveStrategyStage(options: HistoricalBackfillOptions, resolvedEnd: LocalDate): HistoricalBackfillStage {
        val lastContiguousDate = findLastContiguousStrategyDate(resolvedEnd)
        val requestedStart = when {
            options.fromDate != null -> maxOf(options.fromDate, BACKFILL_FLOOR_DATE)
            lastContiguousDate != null -> findNextTradingDate(lastContiguousDate)
            else -> BACKFILL_FLOOR_DATE
        }
        return buildStage(requestedStart, resolvedEnd, lastContiguousDate)
    }

    private fun buildStage(
        requestedStart: LocalDate?,
        resolvedEnd: LocalDate,
        lastContiguousDate: LocalDate?,
    ): HistoricalBackfillStage {
        if (requestedStart == null || requestedStart > resolvedEnd) {
            return HistoricalBackfillStage(
                startDate = null,
                endDate = null,
                tradeDates = emptyList(),
                lastContiguousDate = lastContiguousDate,
            )
        }

        val tradeDates = findOpenDates(requestedStart, resolvedEnd)
        if (tradeDates.isEmpty()) {
            return HistoricalBackfillStage(
                startDate = null,
                endDate = null,
                tradeDates = emptyList(),
                lastContiguousDate = lastContiguousDate,
            )
        }

        return HistoricalBackfillStage(
            startDate = tradeDates.first(),
            endDate = tradeDates.last(),
            tradeDates = tradeDates,
            lastContiguousDate = lastContiguousDate,
        )
    }

    private fun findLastContiguousDailyDate(endDateInclusive: LocalDate): LocalDate? {
        val openDates = findOpenDates(BACKFILL_FLOOR_DATE, endDateInclusive)
        val updatedTradeDates = findDailyUpdatedTradeDates(BACKFILL_FLOOR_DATE, endDateInclusive)
        val existingTradeDates = findExistingTradeDates(BACKFILL_FLOOR_DATE, endDateInclusive)
        var lastComplete: LocalDate? = null
        for (tradeDate in openDates) {
            if (!(updatedTradeDates.contains(tradeDate) && existingTradeDates.contains(tradeDate))) {
                break
            }
            lastComplete = tradeDate
        }
        return lastComplete
    }

    private fun findLastContiguousFqDate(endDateInclusive: LocalDate): LocalDate? {
        val openDates = findOpenDates(BACKFILL_FLOOR_DATE, endDateInclusive)
        val fqUpdatedTradeDates = findFqUpdatedTradeDates(BACKFILL_FLOOR_DATE, endDateInclusive)
        var lastComplete: LocalDate? = null
        for (tradeDate in openDates) {
            if (!fqUpdatedTradeDates.contains(tradeDate)) {
                break
            }
            lastComplete = tradeDate
        }
        return lastComplete
    }

    private fun findLastContiguousStrategyDate(endDateInclusive: LocalDate): LocalDate? {
        val openDates = findOpenDates(BACKFILL_FLOOR_DATE, endDateInclusive)
        val strategyUpdatedTradeDates = findStrategyUpdatedTradeDates(BACKFILL_FLOOR_DATE, endDateInclusive)
        var lastComplete: LocalDate? = null
        for (tradeDate in openDates) {
            if (!strategyUpdatedTradeDates.contains(tradeDate)) {
                break
            }
            lastComplete = tradeDate
        }
        return lastComplete
    }
}
