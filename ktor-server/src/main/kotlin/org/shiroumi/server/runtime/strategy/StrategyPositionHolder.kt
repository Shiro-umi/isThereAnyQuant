package org.shiroumi.server.runtime.strategy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import model.ws.PositionSource
import model.ws.StrategyPositionSnapshot
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.database.strategy.daily.repository.toSelectionSnapshot
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository
import org.shiroumi.server.runtime.market.resolveEffectiveTradeDate
import utils.logger
import java.time.Clock

/**
 * Ktor 端策略持仓 last-known cache。
 *
 * service-first 模式下，本 holder 不再产生任何 snapshot：
 * - [initialize] 在冷启动时尝试从审计表/目标组合表加载历史 last-known，避免客户端首屏空白
 * - [updateFromService] 是唯一写入路径，由订阅服务收到 service `STRATEGY_POSITIONS` snapshot 时同步
 * - [snapshot] 仅供导出 API 与诊断使用
 */
object StrategyPositionHolder {
    private val logger by logger("StrategyPositionHolder")

    private val _snapshot = MutableStateFlow<StrategyPositionSnapshot?>(null)
    val snapshot: StateFlow<StrategyPositionSnapshot?> = _snapshot.asStateFlow()

    /**
     * 冷启动 last-known 加载。
     *
     * 仅在审计交易日 >= 实际有效交易日且当天数据已就绪时才发布，避免把陈旧审计数据推给客户端。
     */
    fun initialize() {
        val snapshot = loadFromAudit(reason = "initialize")
        if (snapshot != null) {
            val effectiveTradeDate = runCatching { resolveEffectiveTradeDate(Clock.systemDefaultZone()) }.getOrNull()
            val auditDate = kotlinx.datetime.LocalDate.parse(snapshot.tradeDate)
            val isTodayDataReady = effectiveTradeDate == null ||
                TradingCalendarRepository.isStrategyUpdated(effectiveTradeDate)
            val isStale = effectiveTradeDate != null && auditDate < effectiveTradeDate && isTodayDataReady
            if (!isStale && effectiveTradeDate != null) {
                _snapshot.value = snapshot.copy(source = PositionSource.DAILY_AUDIT_COMPLETE)
                logger.info(
                    "[initialize] Published audit state (today's data ready): tradeDate=${snapshot.tradeDate}, " +
                        "positions=${snapshot.currentPositions.size}, " +
                        "nextSelections=${snapshot.nextSessionSelections.size}"
                )
            } else {
                logger.info(
                    "[initialize] Loaded audit state (not published, stale=$isStale): " +
                        "tradeDate=${snapshot.tradeDate}, " +
                        "positions=${snapshot.currentPositions.size}, " +
                        "nextSelections=${snapshot.nextSessionSelections.size}"
                )
            }
        }
    }

    /**
     * 从审计表/目标组合表加载历史 last-known，仅供 [initialize] 使用，不直接发布。
     */
    private fun loadFromAudit(reason: String): StrategyPositionSnapshot? {
        val records = DailyStrategyAuditRepository.getRecentRecords(1)
        val summary = records.firstOrNull()
        if (summary == null) {
            logger.info("[$reason] No historical audit records found")
            return null
        }
        val auditPositions = summary.currentPositions
        val positions = auditPositions.ifEmpty {
            DailyProfitPredictionSelectionRepository.findSelectionsByTargetDate(summary.tradeDate)
                .map { it.tsCode }
        }
        val nextSessionSelectionRecords = DailyProfitPredictionSelectionRepository.findSelectionsByTradeDates(listOf(summary.tradeDate))
            .getOrElse(summary.tradeDate) { emptyList() }
        val nextSessionSelections = nextSessionSelectionRecords.map { it.tsCode }
        val newlySelected = nextSessionSelections.filterNot { it in positions }

        val effectiveTradeDate = runCatching { resolveEffectiveTradeDate(Clock.systemDefaultZone()) }.getOrNull()
        val isStale = effectiveTradeDate != null && summary.tradeDate < effectiveTradeDate

        logger.info(
            "[$reason] Loaded audit state: tradeDate=${summary.tradeDate}, " +
                "effectiveTradeDate=$effectiveTradeDate, stale=$isStale, " +
                "positions=${positions.size}, nextSelections=${nextSessionSelections.size}, newlySelected=${newlySelected.size}"
        )
        return StrategyPositionSnapshot(
            tradeDate = summary.tradeDate.toString(),
            currentPositions = positions,
            source = PositionSource.HISTORICAL_AUDIT,
            nextSessionSelections = nextSessionSelections,
            nextSessionSelectionDetails = nextSessionSelectionRecords.map { it.toSelectionSnapshot() },
            newlySelected = newlySelected,
        )
    }

    /**
     * 由订阅服务在收到 service `STRATEGY_POSITIONS` snapshot 时调用，
     * 写入唯一来源是 strategy-service。
     */
    fun updateFromService(snapshot: StrategyPositionSnapshot, reason: String = "strategy-service") {
        _snapshot.value = snapshot
        logger.info(
            "Updated strategy positions from $reason: tradeDate=${snapshot.tradeDate}, " +
                "source=${snapshot.source}, positions=${snapshot.currentPositions.size}, " +
                "nextSelections=${snapshot.nextSessionSelections.size}, newlySelected=${snapshot.newlySelected.size}"
        )
    }
}
