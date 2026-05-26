package org.shiroumi.strategy.service.runtime

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import model.ws.PositionSource
import model.ws.StrategyPositionSnapshot
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository
import org.shiroumi.database.strategy.daily.repository.DailyTargetPortfolioRepository
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.core.audit.StrategyAuditSummary
import org.shiroumi.strategy.service.postmarket.PostMarketOrchestrator
import org.shiroumi.strategy.service.postmarket.PostMarketRebuildPolicy
import utils.logger

class PostMarketStrategyRuntime(
    private val snapshotHub: LocalStrategySnapshotHub<JsonElement>,
    private val json: Json,
    private val dataSource: PostMarketStrategyRuntimeDataSource = DefaultPostMarketStrategyRuntimeDataSource,
    private val trackingRuntime: StrategyPositionTrackingRuntime? = null
) : PostMarketRuntime {
    private val logger by logger("PostMarketStrategyRuntime")

    suspend fun publishLatestPositions(reason: String): PostMarketPositionPublishResult {
        val latestSummary = dataSource.loadLatestAuditSummary()
            ?: return PostMarketPositionPublishResult(
                accepted = false,
                message = "no historical strategy audit found"
            )

        val positions = buildPositionSnapshot(latestSummary.tradeDate, latestSummary)
            ?: return PostMarketPositionPublishResult(
                accepted = false,
                message = "no historical positions found tradeDate=${latestSummary.tradeDate}"
            )
        val positionsEnvelope = snapshotHub.publish(
            StrategyTopic.POSITIONS,
            json.encodeToJsonElement(StrategyPositionSnapshot.serializer(), positions)
        )
        val trackingEnvelope = trackingRuntime?.publishFromPositions(positions)

        logger.info(
            "strategy-service latest positions published reason=$reason tradeDate=${latestSummary.tradeDate} " +
                "positionsVersion=${positionsEnvelope.version} trackingVersion=${trackingEnvelope?.version}"
        )
        return PostMarketPositionPublishResult(
            accepted = true,
            message = "latest positions published tradeDate=${latestSummary.tradeDate}",
            positionsEnvelope = positionsEnvelope,
            trackingEnvelope = trackingEnvelope
        )
    }

    override suspend fun rebuildDate(tradeDate: LocalDate, reason: String?): PostMarketRebuildResult =
        rebuildDates(listOf(tradeDate), reason ?: "rebuild-date")

    override suspend fun rebuildRange(
        startDate: LocalDate,
        endDate: LocalDate,
        reason: String?
    ): PostMarketRebuildResult {
        if (endDate < startDate) {
            return PostMarketRebuildResult(
                accepted = false,
                message = "invalid rebuild range: startDate=$startDate endDate=$endDate"
            )
        }
        val tradeDates = dataSource.findOpenDates(startDate, endDate)
        return rebuildDates(tradeDates, reason ?: "rebuild-range")
    }

    private suspend fun rebuildDates(
        tradeDates: List<LocalDate>,
        reason: String
    ): PostMarketRebuildResult {
        if (tradeDates.isEmpty()) {
            return PostMarketRebuildResult(
                accepted = true,
                message = "no open trade dates to rebuild"
            )
        }

        val result = dataSource.executeTradeDates(tradeDates)
        if (result.failedDate != null) {
            return PostMarketRebuildResult(
                accepted = false,
                message = "post-market rebuild failed failedDate=${result.failedDate} processed=${result.processedDates.size}: " +
                    (result.failure?.message ?: "unknown")
            )
        }

        val latestDate = result.processedDates.lastOrNull() ?: tradeDates.last()
        val positions = buildPositionSnapshot(latestDate)
        val positionsEnvelope = positions?.let { snapshot ->
            snapshotHub.publish(
                StrategyTopic.POSITIONS,
                json.encodeToJsonElement(StrategyPositionSnapshot.serializer(), snapshot)
            )
        }
        val trackingEnvelope = positions?.let { trackingRuntime?.publishFromPositions(it) }

        logger.info(
            "strategy-service post-market rebuild completed reason=$reason " +
                "dates=${tradeDates.first()}..${tradeDates.last()} processed=${result.processedDates.size} " +
                "positionsVersion=${positionsEnvelope?.version}"
        )
        return PostMarketRebuildResult(
            accepted = true,
            message = "post-market rebuild completed processed=${result.processedDates.size}",
            positionsEnvelope = positionsEnvelope,
            trackingEnvelope = trackingEnvelope
        )
    }

    private fun buildPositionSnapshot(
        tradeDate: LocalDate,
        summary: StrategyAuditSummary? = dataSource.loadAuditSummary(tradeDate)
    ): StrategyPositionSnapshot? {
        val currentPositions = summary?.currentPositions.orEmpty()
            .ifEmpty { dataSource.loadCurrentPositionCodes(tradeDate) }
        val nextSessionSelections = dataSource.loadNextSessionSelections(tradeDate)
        val newlySelected = nextSessionSelections.filterNot { it in currentPositions }

        if (currentPositions.isEmpty() && nextSessionSelections.isEmpty()) {
            return null
        }
        return StrategyPositionSnapshot(
            tradeDate = tradeDate.toString(),
            currentPositions = currentPositions,
            source = PositionSource.DAILY_AUDIT_COMPLETE,
            nextSessionSelections = nextSessionSelections,
            newlySelected = newlySelected
        )
    }
}

data class PostMarketRebuildResult(
    val accepted: Boolean,
    val message: String,
    val positionsEnvelope: org.shiroumi.strategy.contract.StrategySnapshotEnvelope<JsonElement>? = null,
    val trackingEnvelope: org.shiroumi.strategy.contract.StrategySnapshotEnvelope<JsonElement>? = null
)

data class PostMarketPositionPublishResult(
    val accepted: Boolean,
    val message: String,
    val positionsEnvelope: org.shiroumi.strategy.contract.StrategySnapshotEnvelope<JsonElement>? = null,
    val trackingEnvelope: org.shiroumi.strategy.contract.StrategySnapshotEnvelope<JsonElement>? = null
)

interface PostMarketStrategyRuntimeDataSource {
    fun findOpenDates(startDate: LocalDate, endDate: LocalDate): List<LocalDate>
    suspend fun executeTradeDates(tradeDates: List<LocalDate>): PostMarketOrchestrator.ExecutionResult
    fun loadLatestAuditSummary(): StrategyAuditSummary?
    fun loadAuditSummary(tradeDate: LocalDate): StrategyAuditSummary?
    fun loadCurrentPositionCodes(tradeDate: LocalDate): List<String>
    fun loadNextSessionSelections(tradeDate: LocalDate): List<String>
}

object DefaultPostMarketStrategyRuntimeDataSource : PostMarketStrategyRuntimeDataSource {
    override fun findOpenDates(startDate: LocalDate, endDate: LocalDate): List<LocalDate> =
        TradingCalendarRepository.findOpenDates(startDate, endDate)

    override suspend fun executeTradeDates(tradeDates: List<LocalDate>): PostMarketOrchestrator.ExecutionResult =
        PostMarketOrchestrator.executeTradeDatesCatching(
            tradeDates = tradeDates,
            policy = PostMarketRebuildPolicy.default(),
        )

    override fun loadLatestAuditSummary(): StrategyAuditSummary? =
        DailyStrategyAuditRepository.getRecentRecords(1).firstOrNull()

    override fun loadAuditSummary(tradeDate: LocalDate): StrategyAuditSummary? =
        DailyStrategyAuditRepository.findByDate(tradeDate)

    override fun loadCurrentPositionCodes(tradeDate: LocalDate): List<String> =
        DailyTargetPortfolioRepository.findSelectionsByTargetDate(tradeDate)
            .map { it.tsCode }

    override fun loadNextSessionSelections(tradeDate: LocalDate): List<String> =
        DailyTargetPortfolioRepository.findSelectionsByTradeDates(listOf(tradeDate))
            .getOrElse(tradeDate) { emptyList() }
            .map { it.tsCode }
}
