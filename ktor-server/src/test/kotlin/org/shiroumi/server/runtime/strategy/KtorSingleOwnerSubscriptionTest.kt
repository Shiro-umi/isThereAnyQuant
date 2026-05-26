package org.shiroumi.server.runtime.strategy

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.shiroumi.server.subscription.intraday.IntradaySnapshotSubscriptionService
import org.shiroumi.server.subscription.strategy.StrategyPositionSubscriptionService
import org.shiroumi.server.subscription.strategy.StrategyPositionTrackingSubscriptionService
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Ktor 单 owner 契约测试。
 *
 * Plan §6 关键约束："ktor 不应产生任何 SERVICE_OWNED_TOPICS 的 snapshot,只能消费 service"。
 *
 * 这套测试以可执行契约的形式锁定该约束，防止后续重构再次引入 ktor 端的 publish/fallback 路径。
 * 每个被锁定的方法名/字段名都在迁移过程中被显式删除过；如果未来有人再添回这些 API，
 * 测试会立即失败并指明哪个边界被打穿。
 */
class KtorSingleOwnerSubscriptionTest {

    @Test
    fun `bridge does not expose any publish path for service owned topics`() {
        val members = StrategyRuntimeBridge::class.declaredMemberFunctions.map { it.name }.toSet()

        val forbidden = listOf(
            "publishIntraday",
            "publishPositions",
            "publishPositionTracking",
            "publishLocalIntraday",
            "publishLocalPositions",
        )
        forbidden.forEach { name ->
            assertFalse(
                name in members,
                "StrategyRuntimeBridge.$name 不应该存在 — ktor 不能反向 publish service-owned topic"
            )
        }
    }

    @Test
    fun `bridge keeps consume only api for service owned topics`() {
        val members = StrategyRuntimeBridge::class.declaredMemberFunctions.map { it.name }.toSet()

        val expected = listOf(
            "currentRemoteIntraday",
            "currentRemotePositions",
            "currentRemotePositionTracking",
            "observeRemoteIntraday",
            "observeRemotePositions",
            "observeRemotePositionTracking",
            "sendCommand",
            "rebuildPostMarketDate",
        )
        expected.forEach { name ->
            assertTrue(
                name in members,
                "StrategyRuntimeBridge.$name 是 ktor 消费 service snapshot 的契约入口，不能删除"
            )
        }
    }

    @Test
    fun `bridge does not retain local snapshot hub for service owned topics`() {
        val properties = StrategyRuntimeBridge::class.declaredMemberProperties.map { it.name }.toSet()

        val forbidden = listOf("intradaySnapshots", "positionSnapshots", "remotePublisher")
        forbidden.forEach { name ->
            assertFalse(
                name in properties,
                "StrategyRuntimeBridge.$name 不应该存在 — ktor 不能再持有 service-owned topic 的本地 hub"
            )
        }
    }

    @Test
    fun `position holder write path is restricted to service updates`() {
        val members = StrategyPositionHolder::class.declaredMemberFunctions.map { it.name }.toSet()

        val forbidden = listOf("refreshFromAudit", "updateFromIntraday")
        forbidden.forEach { name ->
            assertFalse(
                name in members,
                "StrategyPositionHolder.$name 不应该存在 — service-first 模式下 holder 的唯一写入路径是 updateFromService"
            )
        }
        assertTrue(
            "updateFromService" in members,
            "StrategyPositionHolder.updateFromService 是 service snapshot → holder 的唯一写入路径，不能删除"
        )
        assertTrue(
            "initialize" in members,
            "StrategyPositionHolder.initialize 是冷启动 last-known 加载入口，不能删除"
        )
    }

    @Test
    fun `subscription services constructors do not require fallback policies`() {
        listOf(
            IntradaySnapshotSubscriptionService::class to "IntradaySnapshotSubscriptionService",
            StrategyPositionSubscriptionService::class to "StrategyPositionSubscriptionService",
            StrategyPositionTrackingSubscriptionService::class to "StrategyPositionTrackingSubscriptionService",
        ).forEach { (klass, name) ->
            val ctor: KFunction<*> = klass.primaryConstructor
                ?: error("$name 必须有 primary constructor")
            ctor.parameters.forEach { param ->
                val typeName = param.type.toString()
                assertFalse(
                    typeName.contains("FallbackPolicy", ignoreCase = true),
                    "$name 构造器参数 ${param.name} 不应该出现 FallbackPolicy — 已物理删除"
                )
                assertFalse(
                    typeName.contains("ProjectionService", ignoreCase = true),
                    "$name 构造器参数 ${param.name} 不应该出现 ProjectionService — 本地 fallback projection 已物理删除"
                )
            }
        }
    }

    @Test
    fun `subscription services expose ws session lifecycle hooks only`() {
        listOf(
            IntradaySnapshotSubscriptionService::class,
            StrategyPositionSubscriptionService::class,
            StrategyPositionTrackingSubscriptionService::class,
        ).forEach { klass ->
            val members = klass.declaredMemberFunctions.map { it.name }.toSet()
            assertNotNull(
                members.firstOrNull { it == "subscribe" },
                "${klass.simpleName}.subscribe 是 ktor 端建立订阅的唯一入口，不能删除"
            )
            assertNotNull(
                members.firstOrNull { it == "unsubscribe" || it == "cleanupSession" },
                "${klass.simpleName} 必须保留 unsubscribe/cleanupSession 之一以支持会话清理"
            )
        }
    }
}
