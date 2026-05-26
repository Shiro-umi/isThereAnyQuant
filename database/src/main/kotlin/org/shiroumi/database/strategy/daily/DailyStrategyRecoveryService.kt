package org.shiroumi.database.strategy.daily

import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.strategy.daily.repository.DailyFactorRollingStateRepository
import org.shiroumi.database.strategy.daily.repository.DailyMarketSentimentRepository
import org.shiroumi.database.strategy.daily.repository.DailyMarketSentimentStateRepository
import org.shiroumi.database.strategy.daily.repository.DailyStockFactorRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository
import org.shiroumi.database.strategy.daily.repository.DailyTargetPortfolioRepository
import org.shiroumi.database.strategy.daily.repository.SentimentRuntimeSeedRepository

data class DailyStrategyRecoverySummary(
    val tradeDate: LocalDate,
    val marketSentimentCount: Long,
    val marketSentimentStateCount: Long,
    val stockFactorCount: Long,
    val factorRollingStateCount: Long,
    val targetPortfolioCount: Long,
    val strategyAuditCount: Long,
    val runtimeSeedCount: Long,
    val strategyUpdated: Boolean,
)

data class DailyStrategyRecoveryResult(
    val before: DailyStrategyRecoverySummary,
    val after: DailyStrategyRecoverySummary,
    val deletedMarketSentiment: Int,
    val deletedMarketSentimentState: Int,
    val deletedStockFactor: Int,
    val deletedFactorRollingState: Int,
    val deletedTargetPortfolio: Int,
    val deletedStrategyAudit: Int,
    val deletedRuntimeSeeds: Int,
)

object DailyStrategyRecoveryService {
    fun inspect(tradeDate: LocalDate): DailyStrategyRecoverySummary {
        return DailyStrategyRecoverySummary(
            tradeDate = tradeDate,
            marketSentimentCount = DailyMarketSentimentRepository.countByDate(tradeDate),
            marketSentimentStateCount = DailyMarketSentimentStateRepository.countByDate(tradeDate),
            stockFactorCount = DailyStockFactorRepository.countByDate(tradeDate),
            factorRollingStateCount = DailyFactorRollingStateRepository.countByDate(tradeDate),
            targetPortfolioCount = DailyTargetPortfolioRepository.countByDate(tradeDate),
            strategyAuditCount = DailyStrategyAuditRepository.countByDate(tradeDate),
            runtimeSeedCount = SentimentRuntimeSeedRepository.countBySourceTradeDate(tradeDate),
            strategyUpdated = !TradingCalendarRepository.findPendingStrategyDates(tradeDate).contains(tradeDate),
        )
    }

    fun cleanup(tradeDate: LocalDate, dryRun: Boolean): DailyStrategyRecoveryResult {
        val before = inspect(tradeDate)
        if (!dryRun) {
            DailyMarketSentimentRepository.deleteByDate(tradeDate)
            DailyMarketSentimentStateRepository.deleteByDate(tradeDate)
            DailyStockFactorRepository.deleteByDate(tradeDate)
            DailyFactorRollingStateRepository.deleteByDate(tradeDate)
            DailyTargetPortfolioRepository.deleteByDate(tradeDate)
            DailyStrategyAuditRepository.deleteByDate(tradeDate)
            SentimentRuntimeSeedRepository.deleteBySourceTradeDate(tradeDate)
            TradingCalendarRepository.resetStrategyUpdated(tradeDate, tradeDate)
        }
        val after = inspect(tradeDate)
        return DailyStrategyRecoveryResult(
            before = before,
            after = after,
            deletedMarketSentiment = (before.marketSentimentCount - after.marketSentimentCount).toInt(),
            deletedMarketSentimentState = (before.marketSentimentStateCount - after.marketSentimentStateCount).toInt(),
            deletedStockFactor = (before.stockFactorCount - after.stockFactorCount).toInt(),
            deletedFactorRollingState = (before.factorRollingStateCount - after.factorRollingStateCount).toInt(),
            deletedTargetPortfolio = (before.targetPortfolioCount - after.targetPortfolioCount).toInt(),
            deletedStrategyAudit = (before.strategyAuditCount - after.strategyAuditCount).toInt(),
            deletedRuntimeSeeds = (before.runtimeSeedCount - after.runtimeSeedCount).toInt(),
        )
    }
}
