package org.shiroumi.strategy.contract

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Socket frame 序列化 golden test。
 *
 * 验证 contract 序列化格式稳定：任何修改都必须保持向后兼容或显式升级 contractVersion。
 * 如果此测试失败，说明序列化格式已变化，需要同步更新 service 和 client 两端。
 */
class StrategySocketFrameSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "frameType"
    }

    @Test
    fun `subscribe frame serializes to stable format`() {
        val frame = StrategySocketFrame.Subscribe(
            requestId = "req-1",
            contractVersion = 1,
            topics = setOf(StrategyTopic.INTRADAY, StrategyTopic.POSITIONS)
        )

        val serialized = json.encodeToString(StrategySocketFrame.serializer(), frame)
        val parsed = json.decodeFromString(StrategySocketFrame.serializer(), serialized)

        assertTrue(parsed is StrategySocketFrame.Subscribe)
        val subscribe = parsed as StrategySocketFrame.Subscribe
        assertEquals("req-1", subscribe.requestId)
        assertEquals(1, subscribe.contractVersion)
        assertEquals(setOf(StrategyTopic.INTRADAY, StrategyTopic.POSITIONS), subscribe.topics)

        // Golden: 序列化结果必须包含预期的 JSON 结构
        val jsonObject = json.parseToJsonElement(serialized).jsonObject
        assertEquals("subscribe", jsonObject["frameType"]?.jsonPrimitive?.content)
        assertEquals("req-1", jsonObject["requestId"]?.jsonPrimitive?.content)
        assertEquals(1, jsonObject["contractVersion"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `snapshot frame serializes to stable format`() {
        val wire = StrategyWireSnapshot(
            topic = StrategyTopic.INTRADAY,
            version = 7,
            sourceInstanceId = "strategy-service-1",
            publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            contractVersion = 1,
            payload = buildJsonObject {
                put("tradeDate", "2026-04-30")
                put("value", 42)
            }
        )
        val frame = StrategySocketFrame.Snapshot(wire)

        val serialized = json.encodeToString(StrategySocketFrame.serializer(), frame)
        val parsed = json.decodeFromString(StrategySocketFrame.serializer(), serialized)

        assertTrue(parsed is StrategySocketFrame.Snapshot)
        val snapshot = parsed as StrategySocketFrame.Snapshot
        assertEquals(StrategyTopic.INTRADAY, snapshot.snapshot.topic)
        assertEquals(7, snapshot.snapshot.version)
        assertEquals("strategy-service-1", snapshot.snapshot.sourceInstanceId)
        assertEquals(1, snapshot.snapshot.contractVersion)

        val jsonObject = json.parseToJsonElement(serialized).jsonObject
        assertEquals("snapshot", jsonObject["frameType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `command frame serializes to stable format`() {
        val frame = StrategySocketFrame.Command(
            requestId = "cmd-1",
            contractVersion = 1,
            command = StrategyCommand.RebuildDate("2026-04-30", "test")
        )

        val serialized = json.encodeToString(StrategySocketFrame.serializer(), frame)
        val parsed = json.decodeFromString(StrategySocketFrame.serializer(), serialized)

        assertTrue(parsed is StrategySocketFrame.Command)
        val command = parsed as StrategySocketFrame.Command
        assertEquals("cmd-1", command.requestId)
        assertEquals(1, command.contractVersion)
        assertTrue(command.command is StrategyCommand.RebuildDate)

        val jsonObject = json.parseToJsonElement(serialized).jsonObject
        assertEquals("command", jsonObject["frameType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ack frame serializes to stable format`() {
        val frame = StrategySocketFrame.Ack(
            requestId = "ack-1",
            ack = StrategyCommandAck(
                accepted = true,
                message = "ok",
                sourceInstanceId = "strategy-service-1",
                contractVersion = 1
            )
        )

        val serialized = json.encodeToString(StrategySocketFrame.serializer(), frame)
        val parsed = json.decodeFromString(StrategySocketFrame.serializer(), serialized)

        assertTrue(parsed is StrategySocketFrame.Ack)
        val ack = parsed as StrategySocketFrame.Ack
        assertEquals("ack-1", ack.requestId)
        assertEquals(true, ack.ack.accepted)
        assertEquals(1, ack.ack.contractVersion)

        val jsonObject = json.parseToJsonElement(serialized).jsonObject
        assertEquals("ack", jsonObject["frameType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `all commands serialize with correct type discriminator`() {
        val commands = listOf(
            StrategyCommand.HealthCheck to "health-check",
            StrategyCommand.RefreshIntraday("test") to "refresh-intraday",
            StrategyCommand.RebuildDate("2026-04-30") to "rebuild-date",
            StrategyCommand.RebuildRange("2026-04-01", "2026-04-30") to "rebuild-range",
            StrategyCommand.LoadRealtimeDailyCandles(listOf("000001.SZ"), "2026-04-30") to "load-realtime-daily-candles",
            StrategyCommand.BuildCalibratedTracking("2026-04-30") to "build-calibrated-tracking",
        )

        // 注意：StrategySocketFrame 和 StrategyCommand 都使用 classDiscriminator = "type"
        // 直接序列化 command 来验证 discriminator
        val commandJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }

        commands.forEach { (cmd, expectedType) ->
            val serialized = commandJson.encodeToString(StrategyCommand.serializer(), cmd)
            val jsonObj = commandJson.parseToJsonElement(serialized).jsonObject
            assertEquals(expectedType, jsonObj["type"]?.jsonPrimitive?.content, "command type mismatch for $cmd")
        }
    }

    @Test
    fun `strategy topic enum serializes to uppercase name`() {
        StrategyTopic.entries.forEach { topic ->
            val serialized = json.encodeToString(StrategyTopic.serializer(), topic)
            // 去除引号
            val name = serialized.trim('"')
            assertEquals(topic.name, name)
        }
    }

    @Test
    fun `envelope preserves generic payload structure`() {
        val envelope: StrategySnapshotEnvelope<JsonElement> = StrategySnapshotEnvelope(
            topic = StrategyTopic.POSITIONS,
            version = 3,
            sourceInstanceId = "test",
            publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            payload = buildJsonObject { put("status", "ok") }
        )

        val serializer = StrategySnapshotEnvelope.serializer(JsonElement.serializer())
        val serialized = json.encodeToString(serializer, envelope)
        val parsed = json.decodeFromString(serializer, serialized)

        assertEquals(StrategyTopic.POSITIONS, parsed.topic)
        assertEquals(3, parsed.version)
        assertEquals("test", parsed.sourceInstanceId)
        assertEquals("ok", parsed.payload.jsonObject["status"]?.jsonPrimitive?.content)
    }
}
