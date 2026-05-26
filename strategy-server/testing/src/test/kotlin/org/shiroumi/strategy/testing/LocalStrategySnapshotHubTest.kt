package org.shiroumi.strategy.testing

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategyTopic
import kotlin.test.Test

class LocalStrategySnapshotHubTest {
    @Test
    fun `publish keeps version when payload is unchanged`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>(
            sourceInstanceId = "test-service",
            payloadVersionKey = { payload ->
                JsonObject(payload.jsonObject.filterKeys { it != "timestamp" })
            }
        )
        val payload = buildJsonObject {
            put("tradeDate", "2026-04-30")
            put("value", 1)
            put("timestamp", 1)
        }

        val first = hub.publish(StrategyTopic.INTRADAY, payload)
        val second = hub.publish(
            StrategyTopic.INTRADAY,
            buildJsonObject {
                put("tradeDate", "2026-04-30")
                put("value", 1)
                put("timestamp", 2)
            }
        )
        val third = hub.publish(
            StrategyTopic.INTRADAY,
            buildJsonObject {
                put("tradeDate", "2026-04-30")
                put("value", 2)
                put("timestamp", 3)
            }
        )

        assertEquals(1, first.version)
        assertEquals(first, second)
        assertEquals(2, third.version)
    }

    @Test
    fun `publish increments version when payload changes`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>(
            sourceInstanceId = "test-service",
            payloadVersionKey = { payload ->
                JsonObject(payload.jsonObject.filterKeys { it != "timestamp" })
            }
        )

        val first = hub.publish(
            StrategyTopic.INTRADAY,
            buildJsonObject { put("value", 1) }
        )
        val second = hub.publish(
            StrategyTopic.INTRADAY,
            buildJsonObject { put("value", 2) }
        )
        val third = hub.publish(
            StrategyTopic.INTRADAY,
            buildJsonObject { put("value", 3) }
        )

        assertEquals(1, first.version)
        assertEquals(2, second.version)
        assertEquals(3, third.version)
        assertEquals(3, hub.current(StrategyTopic.INTRADAY)?.version)
    }

    @Test
    fun `publishEnvelope preserves external version and sourceInstanceId`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("local-hub")
        hub.publish(StrategyTopic.POSITIONS, buildJsonObject { put("v", 1) })

        val external = StrategySnapshotEnvelope<JsonElement>(
            topic = StrategyTopic.POSITIONS,
            version = 42,
            sourceInstanceId = "external-service",
            publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            payload = buildJsonObject { put("v", 2) }
        )
        hub.publishEnvelope(external)

        val current = hub.current(StrategyTopic.POSITIONS)!!
        assertEquals(42, current.version)
        assertEquals("external-service", current.sourceInstanceId)
    }

    @Test
    fun `observe emits published envelopes`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val emitted = mutableListOf<StrategySnapshotEnvelope<kotlinx.serialization.json.JsonElement>>()

        val job = launch {
            hub.observe(StrategyTopic.INTRADAY).collect { emitted += it }
        }

        // 让 collect 先注册订阅
        yield()

        val envelope = hub.publish(
            StrategyTopic.INTRADAY,
            buildJsonObject { put("data", "hello") }
        )

        // 等待 collect 收到 emission
        while (emitted.isEmpty()) yield()

        assertEquals(1, emitted.size)
        assertEquals(envelope.version, emitted.first().version)
        assertEquals("hello", emitted.first().payload.jsonObject["data"]?.jsonPrimitive?.content)

        job.cancel()
    }

    @Test
    fun `current returns last known snapshot`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")

        assertEquals(null, hub.current(StrategyTopic.INTRADAY))

        hub.publish(StrategyTopic.INTRADAY, buildJsonObject { put("a", 1) })
        assertEquals(1, hub.current(StrategyTopic.INTRADAY)?.version)

        hub.publish(StrategyTopic.INTRADAY, buildJsonObject { put("a", 2) })
        assertEquals(2, hub.current(StrategyTopic.INTRADAY)?.version)
        assertEquals("test-service", hub.current(StrategyTopic.INTRADAY)?.sourceInstanceId)
    }

    @Test
    fun `publishEnvelope with higher version overrides local`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("local-hub")
        hub.publish(StrategyTopic.HEALTH, buildJsonObject { put("status", "old") })
        val localVersion = hub.current(StrategyTopic.HEALTH)?.version

        val external = StrategySnapshotEnvelope<JsonElement>(
            topic = StrategyTopic.HEALTH,
            version = 100,
            sourceInstanceId = "remote-service",
            publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            payload = buildJsonObject { put("status", "new") }
        )
        hub.publishEnvelope(external)

        val current = hub.current(StrategyTopic.HEALTH)!!
        assertEquals(100, current.version)
        assertEquals("new", current.payload.jsonObject["status"]?.jsonPrimitive?.content)
    }
}
