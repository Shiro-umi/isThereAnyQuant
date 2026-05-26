package org.shiroumi.strategy.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.shiroumi.strategy.client.SocketStrategyRuntimeClient
import org.shiroumi.strategy.contract.StrategyCommandAck
import org.shiroumi.strategy.contract.StrategySocketFrame
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.contract.StrategyWireSnapshot
import java.net.ServerSocket

/**
 * Service-first 端到端集成测试。
 *
 * 验证：当 strategy-service 在 INTRADAY/POSITIONS/POSITION_TRACKING 三个 owned topic
 * 同时推送 snapshot 时，使用 socket 客户端的下游消费者（Ktor 模式下的 StrategyRuntimeBridge）
 * 可以按拓扑独立 observe 到全部 topic 的快照。
 */
class StrategyServiceFirstIntegrationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "frameType"
    }

    @Test
    fun `client receives snapshots for all three service-owned topics`() = runBlocking {
        ServerSocket(0).use { server ->
            val fakeService = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    val subscribeFrame = json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        reader.readLine()
                    )
                    require(subscribeFrame is StrategySocketFrame.Subscribe)
                    val expectedOwnedTopics = setOf(
                        StrategyTopic.INTRADAY,
                        StrategyTopic.POSITIONS,
                        StrategyTopic.POSITION_TRACKING
                    )
                    assertTrue(
                        subscribeFrame.topics.containsAll(expectedOwnedTopics),
                        "client must subscribe to all service-owned topics, got=${subscribeFrame.topics}"
                    )
                    writer.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Ack(
                                requestId = subscribeFrame.requestId,
                                ack = StrategyCommandAck(
                                    accepted = true,
                                    message = "subscribed",
                                    sourceInstanceId = "fake-strategy-service"
                                )
                            )
                        )
                    )
                    writer.newLine()

                    listOf(
                        StrategyTopic.INTRADAY to buildJsonObject {
                            put("tradeDate", "2026-04-30")
                            put("topic", "intraday")
                        },
                        StrategyTopic.POSITIONS to buildJsonObject {
                            put("tradeDate", "2026-04-30")
                            put("topic", "positions")
                        },
                        StrategyTopic.POSITION_TRACKING to buildJsonObject {
                            put("tradeDate", "2026-04-30")
                            put("topic", "position_tracking")
                        }
                    ).forEachIndexed { index, (topic, payload) ->
                        writer.write(
                            json.encodeToString(
                                StrategySocketFrame.serializer(),
                                StrategySocketFrame.Snapshot(
                                    StrategyWireSnapshot(
                                        topic = topic,
                                        version = (index + 1).toLong(),
                                        sourceInstanceId = "fake-strategy-service",
                                        publishedAt = Instant.fromEpochMilliseconds(
                                            1_700_000_000_000L + index
                                        ),
                                        payload = payload
                                    )
                                )
                            )
                        )
                        writer.newLine()
                    }
                    writer.flush()
                    delay(200)
                }
            }

            val client = SocketStrategyRuntimeClient(
                host = "127.0.0.1",
                port = server.localPort,
                topics = setOf(
                    StrategyTopic.INTRADAY,
                    StrategyTopic.POSITIONS,
                    StrategyTopic.POSITION_TRACKING,
                    StrategyTopic.HEALTH
                ),
                reconnectDelayMillis = 10_000
            )

            try {
                val intraday = withTimeout(2_000) {
                    var current = client.current(StrategyTopic.INTRADAY)
                    while (current == null) {
                        delay(10)
                        current = client.current(StrategyTopic.INTRADAY)
                    }
                    current
                }
                val positions = withTimeout(2_000) {
                    var current = client.current(StrategyTopic.POSITIONS)
                    while (current == null) {
                        delay(10)
                        current = client.current(StrategyTopic.POSITIONS)
                    }
                    current
                }
                val tracking = withTimeout(2_000) {
                    var current = client.current(StrategyTopic.POSITION_TRACKING)
                    while (current == null) {
                        delay(10)
                        current = client.current(StrategyTopic.POSITION_TRACKING)
                    }
                    current
                }

                assertNotNull(intraday)
                assertNotNull(positions)
                assertNotNull(tracking)
                assertEquals(
                    "intraday",
                    intraday.payload.jsonObject["topic"]?.jsonPrimitive?.content
                )
                assertEquals(
                    "positions",
                    positions.payload.jsonObject["topic"]?.jsonPrimitive?.content
                )
                assertEquals(
                    "position_tracking",
                    tracking.payload.jsonObject["topic"]?.jsonPrimitive?.content
                )
                assertEquals("fake-strategy-service", intraday.sourceInstanceId)
                assertEquals("fake-strategy-service", positions.sourceInstanceId)
                assertEquals("fake-strategy-service", tracking.sourceInstanceId)
            } finally {
                client.close()
                withTimeout(2_000) {
                    withContext(Dispatchers.IO) {
                        fakeService.await()
                    }
                }
            }
        }
    }
}
