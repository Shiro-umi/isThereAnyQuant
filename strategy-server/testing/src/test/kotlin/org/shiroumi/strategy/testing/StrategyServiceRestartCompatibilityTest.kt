package org.shiroumi.strategy.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.shiroumi.strategy.client.SocketStrategyRuntimeClient
import org.shiroumi.strategy.contract.StrategyCommandAck
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategySocketFrame
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.contract.StrategyWireSnapshot
import java.net.ServerSocket

/**
 * Service restart end-to-end 兼容性测试。
 *
 * 验证场景：
 * 1. service (source-a) 连接并推送 POSITIONS snapshot
 * 2. service 断开（模拟重启）
 * 3. client 保留 last-known POSITIONS snapshot
 * 4. client HEALTH 标记为 DISCONNECTED
 * 5. 新 service (source-b) 重连并推送新 snapshot
 * 6. client 切换到 source-b，observe 流收到新 snapshot
 * 7. Ktor 侧前端订阅不受影响（本测试验证 client 侧语义）
 */
class StrategyServiceRestartCompatibilityTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "frameType"
    }

    @Test
    fun `service restart retains last known snapshot and switches source instance`() = runBlocking {
        ServerSocket(0).use { server ->
            val serviceA = "service-a"
            val serviceB = "service-b"

            val fakeService = async(Dispatchers.IO) {
                // Phase 1: service-a 连接，推送 POSITIONS
                server.accept().use { socketA ->
                    val readerA = socketA.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writerA = socketA.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    // 读取 subscribe
                    val subscribeFrame = json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        readerA.readLine()
                    )
                    require(subscribeFrame is StrategySocketFrame.Subscribe)

                    // 发送 ack
                    writerA.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Ack(
                                requestId = subscribeFrame.requestId,
                                ack = StrategyCommandAck(accepted = true, message = "subscribed")
                            )
                        )
                    )
                    writerA.newLine()

                    // 发送 service-a 的 POSITIONS snapshot
                    writerA.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Snapshot(
                                StrategyWireSnapshot(
                                    topic = StrategyTopic.POSITIONS,
                                    version = 1,
                                    sourceInstanceId = serviceA,
                                    publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
                                    payload = buildJsonObject {
                                        put("tradeDate", "2026-04-30")
                                        put("source", "a")
                                    }
                                )
                            )
                        )
                    )
                    writerA.newLine()
                    writerA.flush()

                    // 等待 client 读取 snapshot 后断开（模拟 service 重启）
                    delay(200)
                }

                // Phase 2: service-b 重连，推送新 POSITIONS
                server.accept().use { socketB ->
                    val readerB = socketB.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writerB = socketB.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    val subscribeFrameB = json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        readerB.readLine()
                    )
                    require(subscribeFrameB is StrategySocketFrame.Subscribe)

                    writerB.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Ack(
                                requestId = subscribeFrameB.requestId,
                                ack = StrategyCommandAck(accepted = true, message = "subscribed")
                            )
                        )
                    )
                    writerB.newLine()

                    // 发送 service-b 的 POSITIONS snapshot（更高 version + 不同 source）
                    writerB.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Snapshot(
                                StrategyWireSnapshot(
                                    topic = StrategyTopic.POSITIONS,
                                    version = 2,
                                    sourceInstanceId = serviceB,
                                    publishedAt = Instant.fromEpochMilliseconds(1_700_000_001_000),
                                    payload = buildJsonObject {
                                        put("tradeDate", "2026-04-30")
                                        put("source", "b")
                                    }
                                )
                            )
                        )
                    )
                    writerB.newLine()
                    writerB.flush()
                    delay(200)
                }
            }

            val client = SocketStrategyRuntimeClient(
                host = "127.0.0.1",
                port = server.localPort,
                topics = setOf(StrategyTopic.POSITIONS, StrategyTopic.HEALTH),
                reconnectDelayMillis = 100
            )

            // Step 0: 提前启动 observe（MutableSharedFlow replay=0，必须在 emit 前订阅）
            val observedSnapshots = mutableListOf<String>()
            val observeJob = launch {
                client.observe(StrategyTopic.POSITIONS).collect {
                    observedSnapshots += it.sourceInstanceId
                }
            }

            // Step 1: 等待 service-a 的 POSITIONS snapshot 到达
            val snapshotA = withTimeout(2_000) {
                var current: StrategySnapshotEnvelope<JsonElement>? = null
                while (current == null || current.sourceInstanceId != serviceA) {
                    delay(10)
                    current = client.current(StrategyTopic.POSITIONS)
                }
                current
            }
            assertNotNull(snapshotA)
            assertEquals(serviceA, snapshotA.sourceInstanceId)
            assertEquals(1, snapshotA.version)
            assertEquals("a", snapshotA.payload.jsonObject["source"]?.jsonPrimitive?.content)

            // Step 2: 等待 service 断开，HEALTH 变为 DISCONNECTED
            val healthDisconnected = withTimeout(2_000) {
                var current: StrategySnapshotEnvelope<JsonElement>? = null
                while (current?.payload?.jsonObject?.get("status")?.jsonPrimitive?.content != "DISCONNECTED") {
                    delay(10)
                    current = client.current(StrategyTopic.HEALTH)
                }
                current
            }
            assertEquals("DISCONNECTED", healthDisconnected.payload.jsonObject["status"]?.jsonPrimitive?.content)

            // Step 3: 断线期间 last-known POSITIONS 仍应保留
            val lastKnownDuringDisconnect = client.current(StrategyTopic.POSITIONS)
            assertNotNull(lastKnownDuringDisconnect)
            assertEquals(serviceA, lastKnownDuringDisconnect?.sourceInstanceId)
            assertEquals(1, lastKnownDuringDisconnect?.version)

            // Step 4: 等待 service-b 重连后 POSITIONS snapshot 到达
            val snapshotB = withTimeout(5_000) {
                var current: StrategySnapshotEnvelope<JsonElement>? = null
                while (current == null || current.sourceInstanceId != serviceB) {
                    delay(10)
                    current = client.current(StrategyTopic.POSITIONS)
                }
                current
            }
            assertNotNull(snapshotB)
            assertEquals(serviceB, snapshotB.sourceInstanceId)
            assertEquals(2, snapshotB.version)
            assertEquals("b", snapshotB.payload.jsonObject["source"]?.jsonPrimitive?.content)

            // Step 5: 验证 observe 流也收到 snapshot（service-a 或 service-b 均可）
            // observeJob 在 Step 0 已启动， MutableSharedFlow replay=0，但持续 collect 应能收到
            assertTrue(
                serviceA in observedSnapshots || serviceB in observedSnapshots,
                "observe 流应收到至少一个 snapshot, got=$observedSnapshots"
            )

            // Step 6: HEALTH 应恢复为 CONNECTED
            val healthConnected = withTimeout(2_000) {
                var current: StrategySnapshotEnvelope<JsonElement>? = null
                while (current?.payload?.jsonObject?.get("status")?.jsonPrimitive?.content != "CONNECTED") {
                    delay(10)
                    current = client.current(StrategyTopic.HEALTH)
                }
                current
            }
            assertEquals("CONNECTED", healthConnected.payload.jsonObject["status"]?.jsonPrimitive?.content)

            observeJob.cancel()
            client.close()

            withTimeout(2_000) {
                fakeService.await()
            }
        }
    }

    @Test
    fun `service restart switches source instance and lower version is accepted by hub`() = runBlocking {
        ServerSocket(0).use { server ->
            val fakeService = async(Dispatchers.IO) {
                // Phase 1: service-a 推送 version=5
                server.accept().use { socketA ->
                    val readerA = socketA.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writerA = socketA.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    json.decodeFromString(StrategySocketFrame.serializer(), readerA.readLine())

                    writerA.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Snapshot(
                                StrategyWireSnapshot(
                                    topic = StrategyTopic.POSITIONS,
                                    version = 5,
                                    sourceInstanceId = "service-a",
                                    publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
                                    payload = buildJsonObject { put("v", 5) }
                                )
                            )
                        )
                    )
                    writerA.newLine()
                    writerA.flush()
                    delay(200)
                }

                // Phase 2: service-b（新 sourceInstanceId）推送 version=3
                // LocalStrategySnapshotHub 不做 per-sourceInstanceId version 过滤，
                // version 过滤在 Ktor 侧 StrategySnapshotCursor 完成
                server.accept().use { socketB ->
                    val readerB = socketB.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writerB = socketB.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    json.decodeFromString(StrategySocketFrame.serializer(), readerB.readLine())

                    writerB.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Snapshot(
                                StrategyWireSnapshot(
                                    topic = StrategyTopic.POSITIONS,
                                    version = 3,
                                    sourceInstanceId = "service-b",
                                    publishedAt = Instant.fromEpochMilliseconds(1_700_000_001_000),
                                    payload = buildJsonObject { put("v", 3) }
                                )
                            )
                        )
                    )
                    writerB.newLine()
                    writerB.flush()
                    delay(500)
                }
            }

            val client = SocketStrategyRuntimeClient(
                host = "127.0.0.1",
                port = server.localPort,
                topics = setOf(StrategyTopic.POSITIONS),
                reconnectDelayMillis = 100
            )

            // 等待 version=5 到达
            withTimeout(2_000) {
                while (client.current(StrategyTopic.POSITIONS)?.version != 5L) {
                    delay(10)
                }
            }

            // 等待重连后
            delay(800)

            // hub 层面接受新 sourceInstanceId 的 version=3（per-source 过滤在 cursor 侧）
            val current = client.current(StrategyTopic.POSITIONS)
            assertNotNull(current)
            assertEquals(3, current?.version)
            assertEquals("service-b", current?.sourceInstanceId)

            client.close()
            withTimeout(2_000) { fakeService.await() }
        }
    }
}
