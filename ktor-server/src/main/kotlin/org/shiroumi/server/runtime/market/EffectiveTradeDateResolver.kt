package org.shiroumi.server.runtime.market

import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.repository.TradingCalendarRepository
import java.time.Clock

/**
 * 把“当前自然日”解析成当前链路应使用的交易日。
 *
 * 周末/节假日应回落到最近一个开市日；若交易日历暂不可用，则兜底返回自然日。
 */
fun resolveEffectiveTradeDate(clock: Clock): LocalDate {
    val today = java.time.LocalDate.now(clock)
        .let { LocalDate(it.year, it.monthValue, it.dayOfMonth) }
    return runCatching {
        TradingCalendarRepository.findLatestTradingDateOnOrBefore(today)
    }.getOrNull() ?: today
}
