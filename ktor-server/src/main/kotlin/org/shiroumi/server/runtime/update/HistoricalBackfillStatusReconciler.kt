package org.shiroumi.server.runtime.update

import kotlinx.datetime.LocalDate
import model.DataUpdateStatus
import org.shiroumi.database.common.repository.SystemStatusRepository
import org.shiroumi.database.common.repository.TradingCalendarRepository
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId

private val BACKFILL_STATUS_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")
private val BACKFILL_STATUS_CUTOFF: LocalTime = LocalTime.of(16, 30)

/**
 * 手动 backfill 成功后，按“前一个应完成交易日是否已经补齐”来回正全局更新状态。
 *
 * 业务语义上，Snackbar 关心的是“前一天盘后历史数据是否已经准备好”，
 * 所以 backfill 追平后也应该把遗留的 failed/updating 状态纠正回来。
 */
class HistoricalBackfillStatusReconciler(
    private val clock: Clock = Clock.system(BACKFILL_STATUS_ZONE_ID),
    private val latestTradingDateOnOrBefore: (LocalDate) -> LocalDate? = {
        TradingCalendarRepository.findLatestTradingDateOnOrBefore(it)
    },
    private val previousTradingDate: (LocalDate) -> LocalDate? = {
        TradingCalendarRepository.findPreviousTradingDate(it)
    },
    private val isStockDailyUpdated: (LocalDate) -> Boolean = {
        TradingCalendarRepository.isStockDailyUpdated(it)
    },
    private val isStockDailyFqUpdated: (LocalDate) -> Boolean = {
        TradingCalendarRepository.isStockDailyFqUpdated(it)
    },
    private val isStrategyUpdated: (LocalDate) -> Boolean = {
        TradingCalendarRepository.isStrategyUpdated(it)
    },
    private val loadCurrentStatus: () -> DataUpdateStatus? = {
        SystemStatusRepository.getDataUpdateStatus()
    },
    private val saveStatus: (DataUpdateStatus) -> Unit = {
        SystemStatusRepository.saveDataUpdateStatus(it)
    },
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun reconcile() {
        val targetTradeDate = resolveLatestExpectedTradeDate() ?: return
        if (!isTradeDateFullyPrepared(targetTradeDate)) return

        val existing = loadCurrentStatus()
        saveStatus(
            DataUpdateStatus(
                state = DataUpdateStatus.STATE_IDLE,
                lastUpdateTime = currentTimeMillis(),
                message = ""
            )
        )
    }

    private fun resolveLatestExpectedTradeDate(): LocalDate? {
        val now = java.time.LocalDateTime.now(clock)
        val today = LocalDate(now.year, now.monthValue, now.dayOfMonth)
        return if (now.toLocalTime() < BACKFILL_STATUS_CUTOFF) {
            previousTradingDate(today)
        } else {
            latestTradingDateOnOrBefore(today)
        }
    }

    private fun isTradeDateFullyPrepared(tradeDate: LocalDate): Boolean {
        return isStockDailyUpdated(tradeDate) &&
            isStockDailyFqUpdated(tradeDate) &&
            isStrategyUpdated(tradeDate)
    }
}
