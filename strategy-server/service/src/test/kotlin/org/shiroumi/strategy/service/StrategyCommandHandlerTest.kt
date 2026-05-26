package org.shiroumi.strategy.service

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.StrategyCommand
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.service.runtime.IntradayRefreshResult
import org.shiroumi.strategy.service.runtime.IntradayRuntime
import org.shiroumi.strategy.service.runtime.PostMarketRebuildResult
import org.shiroumi.strategy.service.runtime.PostMarketRuntime

class StrategyCommandHandlerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serviceInstanceId = "test-service"

    @Test
    fun `health check returns accepted ack`() = runTest {
        val handler = createHandler()

        val ack = handler.handle(StrategyCommand.HealthCheck)

        assertTrue(ack.accepted)
        assertEquals("strategy-service socket runtime available", ack.message)
        assertEquals(serviceInstanceId, ack.sourceInstanceId)
    }

    @Test
    fun `refresh intraday delegates to runtime and returns ack`() = runTest {
        val hub = LocalStrategySnapshotHub<JsonElement>(serviceInstanceId)
        val runtime = FakeIntradayRuntime(
            result = IntradayRefreshResult(
                accepted = true,
                message = "refreshed",
                intradayEnvelope = hub.publish(StrategyTopic.INTRADAY, buildPayload("intraday")),
                positionsEnvelope = hub.publish(StrategyTopic.POSITIONS, buildPayload("positions")),
            )
        )
        val handler = createHandler(intradayRuntime = runtime, hub = hub)

        val ack = handler.handle(StrategyCommand.RefreshIntraday("test-refresh"))

        assertTrue(ack.accepted)
        assertEquals("refreshed", ack.message)
        assertEquals(1, runtime.refreshCallCount)
        assertEquals("test-refresh", runtime.lastReason)

        val health = hub.current(StrategyTopic.HEALTH)
        assertNotNull(health)
        assertEquals("INTRADAY_REFRESHED", health!!.payload.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `refresh intraday failure publishes health and returns rejected ack`() = runTest {
        val runtime = FakeIntradayRuntime(
            result = IntradayRefreshResult(accepted = false, message = "universe empty")
        )
        val hub = LocalStrategySnapshotHub<JsonElement>(serviceInstanceId)
        val handler = createHandler(intradayRuntime = runtime, hub = hub)

        val ack = handler.handle(StrategyCommand.RefreshIntraday("test-fail"))

        assertFalse(ack.accepted)
        assertEquals("universe empty", ack.message)

        val health = hub.current(StrategyTopic.HEALTH)
        assertNotNull(health)
        assertEquals("INTRADAY_REFRESH_FAILED", health!!.payload.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `rebuild date delegates to post market runtime and returns ack`() = runTest {
        val hub = LocalStrategySnapshotHub<JsonElement>(serviceInstanceId)
        val runtime = FakePostMarketRuntime(
            rebuildDateResult = PostMarketRebuildResult(
                accepted = true,
                message = "rebuilt",
                positionsEnvelope = hub.publish(StrategyTopic.POSITIONS, buildPayload("positions")),
            )
        )
        val handler = createHandler(postMarketRuntime = runtime)

        val ack = handler.handle(StrategyCommand.RebuildDate("2026-04-30", "test-rebuild"))

        assertTrue(ack.accepted)
        assertEquals("rebuilt", ack.message)
        assertEquals(LocalDate(2026, 4, 30), runtime.lastRebuildDate)
        assertEquals("test-rebuild", runtime.lastReason)
    }

    @Test
    fun `rebuild date invalid parse returns explicit error ack`() = runTest {
        val handler = createHandler()

        val ack = handler.handle(StrategyCommand.RebuildDate("not-a-date", "test"))

        assertFalse(ack.accepted)
        assertTrue(
            ack.message?.contains("invalid RebuildDate command") == true,
            "expected error message to contain 'invalid RebuildDate command', got=${ack.message}"
        )
    }

    @Test
    fun `rebuild range delegates to post market runtime and returns ack`() = runTest {
        val hub = LocalStrategySnapshotHub<JsonElement>(serviceInstanceId)
        val runtime = FakePostMarketRuntime(
            rebuildRangeResult = PostMarketRebuildResult(
                accepted = true,
                message = "range rebuilt",
                positionsEnvelope = hub.publish(StrategyTopic.POSITIONS, buildPayload("positions")),
            )
        )
        val handler = createHandler(postMarketRuntime = runtime)

        val ack = handler.handle(
            StrategyCommand.RebuildRange("2026-04-01", "2026-04-30", "test-range")
        )

        assertTrue(ack.accepted)
        assertEquals("range rebuilt", ack.message)
        assertEquals(LocalDate(2026, 4, 1), runtime.lastRangeStart)
        assertEquals(LocalDate(2026, 4, 30), runtime.lastRangeEnd)
    }

    @Test
    fun `rebuild range invalid parse returns explicit error ack`() = runTest {
        val handler = createHandler()

        val ack = handler.handle(StrategyCommand.RebuildRange("bad", "2026-04-30", "test"))

        assertFalse(ack.accepted)
        assertTrue(
            ack.message?.contains("invalid RebuildRange command") == true,
            "expected error message to contain 'invalid RebuildRange command', got=${ack.message}"
        )
    }

    @Test
    fun `rebuild date failure publishes health and returns rejected ack`() = runTest {
        val hub = LocalStrategySnapshotHub<JsonElement>(serviceInstanceId)
        val runtime = FakePostMarketRuntime(
            rebuildDateResult = PostMarketRebuildResult(
                accepted = false,
                message = "daily facts missing",
            )
        )
        val handler = createHandler(postMarketRuntime = runtime, hub = hub)

        val ack = handler.handle(StrategyCommand.RebuildDate("2026-04-30", "test-fail"))

        assertFalse(ack.accepted)
        assertEquals("daily facts missing", ack.message)

        val health = hub.current(StrategyTopic.HEALTH)
        assertNotNull(health)
        assertEquals("POST_MARKET_REBUILD_FAILED", health!!.payload.jsonObject["status"]?.jsonPrimitive?.content)
    }

    private fun buildPayload(content: String) = kotlinx.serialization.json.buildJsonObject {
        put("content", content)
    }

    private fun createHandler(
        intradayRuntime: IntradayRuntime = FakeIntradayRuntime(),
        postMarketRuntime: PostMarketRuntime = FakePostMarketRuntime(),
        hub: LocalStrategySnapshotHub<JsonElement> = LocalStrategySnapshotHub(serviceInstanceId),
    ): StrategyCommandHandler = StrategyCommandHandler(
        serviceInstanceId = serviceInstanceId,
        intradayRuntime = intradayRuntime,
        postMarketRuntime = postMarketRuntime,
        snapshotHub = hub,
        json = json,
    )
}

private class FakeIntradayRuntime(
    private val result: IntradayRefreshResult = IntradayRefreshResult(
        accepted = true,
        message = "ok",
    ),
) : IntradayRuntime {
    var refreshCallCount = 0
    var lastReason: String? = null

    override suspend fun refresh(reason: String): IntradayRefreshResult {
        refreshCallCount++
        lastReason = reason
        return result
    }
}

private class FakePostMarketRuntime(
    private val rebuildDateResult: PostMarketRebuildResult? = null,
    private val rebuildRangeResult: PostMarketRebuildResult? = null,
) : PostMarketRuntime {
    var lastRebuildDate: LocalDate? = null
    var lastReason: String? = null
    var lastRangeStart: LocalDate? = null
    var lastRangeEnd: LocalDate? = null

    override suspend fun rebuildDate(tradeDate: LocalDate, reason: String?): PostMarketRebuildResult {
        lastRebuildDate = tradeDate
        lastReason = reason
        return rebuildDateResult ?: PostMarketRebuildResult(accepted = true, message = "ok")
    }

    override suspend fun rebuildRange(
        startDate: LocalDate,
        endDate: LocalDate,
        reason: String?,
    ): PostMarketRebuildResult {
        lastRangeStart = startDate
        lastRangeEnd = endDate
        lastReason = reason
        return rebuildRangeResult ?: PostMarketRebuildResult(accepted = true, message = "ok")
    }
}
