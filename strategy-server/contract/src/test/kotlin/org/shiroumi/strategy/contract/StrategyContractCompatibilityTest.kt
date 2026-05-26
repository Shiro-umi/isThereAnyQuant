package org.shiroumi.strategy.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract version 兼容性测试。
 *
 * 验证 service 和 client 对 contractVersion 的判断逻辑一致：
 * - 相同 version = 兼容
 * - 不同 version = 不兼容
 *
 * 当升级 contract 时，必须同步更新 STRATEGY_CONTRACT_VERSION 并确保两端同时部署。
 */
class StrategyContractCompatibilityTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "frameType"
    }

    @Test
    fun `current contract version is positive`() {
        assertTrue(STRATEGY_CONTRACT_VERSION > 0, "contract version must be positive")
    }

    @Test
    fun `compatible versions are equal`() {
        val currentVersion = STRATEGY_CONTRACT_VERSION
        assertTrue(isCompatible(currentVersion), "same version must be compatible")
    }

    @Test
    fun `incompatible versions are not equal`() {
        val incompatibleVersion = STRATEGY_CONTRACT_VERSION + 1
        assertFalse(isCompatible(incompatibleVersion), "different version must be incompatible")
    }

    @Test
    fun `zero version is incompatible`() {
        assertFalse(isCompatible(0), "zero version must be incompatible")
    }

    @Test
    fun `negative version is incompatible`() {
        assertFalse(isCompatible(-1), "negative version must be incompatible")
    }

    @Test
    fun `wire snapshot with compatible version parses correctly`() {
        val wire = StrategyWireSnapshot(
            topic = StrategyTopic.HEALTH,
            version = 1,
            sourceInstanceId = "test",
            publishedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(1),
            contractVersion = STRATEGY_CONTRACT_VERSION,
            payload = buildJsonObject { put("status", "ok") }
        )

        val serialized = json.encodeToString(StrategyWireSnapshot.serializer(), wire)
        val parsed = json.decodeFromString(StrategyWireSnapshot.serializer(), serialized)

        assertEquals(STRATEGY_CONTRACT_VERSION, parsed.contractVersion)
        assertTrue(isCompatible(parsed.contractVersion))
    }

    @Test
    fun `wire snapshot with incompatible version is detectable`() {
        val wire = StrategyWireSnapshot(
            topic = StrategyTopic.HEALTH,
            version = 1,
            sourceInstanceId = "test",
            publishedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(1),
            contractVersion = 999,
            payload = buildJsonObject { put("status", "ok") }
        )

        val serialized = json.encodeToString(StrategyWireSnapshot.serializer(), wire)
        val parsed = json.decodeFromString(StrategyWireSnapshot.serializer(), serialized)

        assertEquals(999, parsed.contractVersion)
        assertFalse(isCompatible(parsed.contractVersion))
    }

    @Test
    fun `command ack carries contract version`() {
        val ack = StrategyCommandAck(
            accepted = true,
            message = "ok",
            sourceInstanceId = "service",
            contractVersion = STRATEGY_CONTRACT_VERSION
        )

        val serialized = json.encodeToString(StrategyCommandAck.serializer(), ack)
        val parsed = json.decodeFromString(StrategyCommandAck.serializer(), serialized)

        assertEquals(STRATEGY_CONTRACT_VERSION, parsed.contractVersion)
    }

    @Test
    fun `all frame types carry contract version where required`() {
        val subscribe = StrategySocketFrame.Subscribe(
            requestId = "req",
            contractVersion = STRATEGY_CONTRACT_VERSION,
            topics = setOf(StrategyTopic.INTRADAY)
        )
        val command = StrategySocketFrame.Command(
            requestId = "cmd",
            contractVersion = STRATEGY_CONTRACT_VERSION,
            command = StrategyCommand.HealthCheck
        )

        val subscribeJson = json.encodeToString(StrategySocketFrame.serializer(), subscribe)
        val commandJson = json.encodeToString(StrategySocketFrame.serializer(), command)

        assertTrue(subscribeJson.contains(""""contractVersion":$STRATEGY_CONTRACT_VERSION"""))
        assertTrue(commandJson.contains(""""contractVersion":$STRATEGY_CONTRACT_VERSION"""))
    }

    private fun isCompatible(contractVersion: Int): Boolean =
        contractVersion == STRATEGY_CONTRACT_VERSION
}
