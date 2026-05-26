package org.shiroumi.strategy.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.shiroumi.strategy.client.SocketStrategyRuntimeClient
import org.shiroumi.strategy.contract.StrategyCommand
import org.shiroumi.strategy.contract.StrategyCommandAck
import org.shiroumi.strategy.contract.StrategySocketFrame
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.contract.StrategyWireSnapshot
import java.net.ServerSocket

class SocketStrategyRuntimeClientTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "frameType"
    }

    @Test
    fun `socket runtime client subscribes snapshots and receives command ack`() = runBlocking {
        ServerSocket(0).use { server ->
            val fakeServer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    val subscribeFrame = json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        reader.readLine()
                    )
                    require(subscribeFrame is StrategySocketFrame.Subscribe)
                    writer.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Ack(
                                requestId = subscribeFrame.requestId,
                                ack = StrategyCommandAck(
                                    accepted = true,
                                    message = "subscribed"
                                )
                            )
                        )
                    )
                    writer.newLine()
                    writer.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Snapshot(
                                StrategyWireSnapshot(
                                    topic = StrategyTopic.INTRADAY,
                                    version = 7,
                                    sourceInstanceId = "fake-strategy-service",
                                    publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
                                    payload = buildJsonObject {
                                        put("tradeDate", "2026-04-30")
                                    }
                                )
                            )
                        )
                    )
                    writer.newLine()
                    writer.flush()

                    val commandFrame = json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        reader.readLine()
                    )
                    require(commandFrame is StrategySocketFrame.Command)
                    writer.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Ack(
                                requestId = commandFrame.requestId,
                                ack = StrategyCommandAck(
                                    accepted = true,
                                    message = "healthy",
                                    sourceInstanceId = "fake-strategy-service"
                                )
                            )
                        )
                    )
                    writer.newLine()
                    writer.flush()
                }
            }

            val client = SocketStrategyRuntimeClient(
                host = "127.0.0.1",
                port = server.localPort,
                topics = setOf(StrategyTopic.INTRADAY),
                reconnectDelayMillis = 10_000
            )

            val snapshot = withTimeout(2_000) {
                var current = client.current(StrategyTopic.INTRADAY)
                while (current == null) {
                    delay(10)
                    current = client.current(StrategyTopic.INTRADAY)
                }
                current
            }
            assertNotNull(snapshot)
            assertEquals(7, snapshot.version)
            assertEquals("fake-strategy-service", snapshot.sourceInstanceId)

            val ack = withTimeout(2_000) {
                client.send(StrategyCommand.HealthCheck)
            }
            assertEquals(true, ack.accepted)
            assertEquals("fake-strategy-service", ack.sourceInstanceId)

            client.close()
            withTimeout(2_000) {
                withContext(Dispatchers.IO) {
                    fakeServer.await()
                }
            }
        }
    }

    @Test
    fun `socket runtime client handles command frames from service`() = runBlocking {
        ServerSocket(0).use { server ->
            val fakeServer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    val subscribeFrame = json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        reader.readLine()
                    )
                    require(subscribeFrame is StrategySocketFrame.Subscribe)

                    writer.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Command(
                                requestId = "load-1",
                                command = StrategyCommand.LoadRealtimeDailyCandles(
                                    tsCodes = listOf("000001.SZ"),
                                    tradeDate = "2026-04-30"
                                )
                            )
                        )
                    )
                    writer.newLine()
                    writer.flush()

                    val ackFrame = json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        reader.readLine()
                    )
                    require(ackFrame is StrategySocketFrame.Ack)
                    ackFrame
                }
            }

            val client = SocketStrategyRuntimeClient(
                host = "127.0.0.1",
                port = server.localPort,
                topics = setOf(StrategyTopic.INTRADAY),
                commandHandler = { command ->
                    require(command is StrategyCommand.LoadRealtimeDailyCandles)
                    StrategyCommandAck(
                        accepted = true,
                        message = "loaded",
                        payload = buildJsonObject { put("count", command.tsCodes.size) }
                    )
                },
                reconnectDelayMillis = 10_000
            )

            val ackFrame = withTimeout(2_000) {
                withContext(Dispatchers.IO) {
                    fakeServer.await()
                }
            }
            assertEquals("load-1", ackFrame.requestId)
            assertEquals(true, ackFrame.ack.accepted)
            assertEquals("1", ackFrame.ack.payload?.jsonObject?.get("count")?.jsonPrimitive?.content)

            client.close()
        }
    }

    @Test
    fun `socket runtime client returns failure ack when command handler throws`() = runBlocking {
        ServerSocket(0).use { server ->
            val fakeServer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    val subscribeFrame = json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        reader.readLine()
                    )
                    require(subscribeFrame is StrategySocketFrame.Subscribe)

                    writer.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Command(
                                requestId = "load-failed",
                                command = StrategyCommand.LoadRealtimeDailyCandles(
                                    tsCodes = listOf("000001.SZ"),
                                    tradeDate = "2026-04-30"
                                )
                            )
                        )
                    )
                    writer.newLine()
                    writer.flush()

                    val ackFrame = json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        reader.readLine()
                    )
                    require(ackFrame is StrategySocketFrame.Ack)
                    ackFrame
                }
            }

            val client = SocketStrategyRuntimeClient(
                host = "127.0.0.1",
                port = server.localPort,
                topics = setOf(StrategyTopic.INTRADAY),
                commandHandler = { error("snapshot unavailable") },
                reconnectDelayMillis = 10_000
            )

            val ackFrame = withTimeout(2_000) {
                withContext(Dispatchers.IO) {
                    fakeServer.await()
                }
            }
            assertEquals("load-failed", ackFrame.requestId)
            assertEquals(false, ackFrame.ack.accepted)
            assertEquals("socket client command handler failed: snapshot unavailable", ackFrame.ack.message)

            client.close()
        }
    }

    @Test
    fun `socket runtime client rejects incompatible snapshot contract version`() = runBlocking {
        ServerSocket(0).use { server ->
            val fakeServer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    val subscribeFrame = json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        reader.readLine()
                    )
                    require(subscribeFrame is StrategySocketFrame.Subscribe)
                    writer.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Snapshot(
                                StrategyWireSnapshot(
                                    topic = StrategyTopic.INTRADAY,
                                    version = 1,
                                    sourceInstanceId = "future-strategy-service",
                                    publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
                                    contractVersion = 999,
                                    payload = buildJsonObject {
                                        put("tradeDate", "2026-04-30")
                                    }
                                )
                            )
                        )
                    )
                    writer.newLine()
                    writer.flush()
                    delay(100)
                }
            }

            val client = SocketStrategyRuntimeClient(
                host = "127.0.0.1",
                port = server.localPort,
                topics = setOf(StrategyTopic.INTRADAY),
                reconnectDelayMillis = 10_000
            )

            val health = withTimeout(2_000) {
                var current = client.current(StrategyTopic.HEALTH)
                while (current?.payload?.jsonObject?.get("status")?.jsonPrimitive?.content !=
                    "CONTRACT_VERSION_MISMATCH"
                ) {
                    delay(10)
                    current = client.current(StrategyTopic.HEALTH)
                }
                current
            }
            assertEquals("CONTRACT_VERSION_MISMATCH", health.payload.jsonObject["status"]?.jsonPrimitive?.content)

            val intraday = withTimeoutOrNull(300) {
                var current = client.current(StrategyTopic.INTRADAY)
                while (current == null) {
                    delay(10)
                    current = client.current(StrategyTopic.INTRADAY)
                }
                current
            }
            assertNull(intraday)

            client.close()
            withTimeout(2_000) {
                withContext(Dispatchers.IO) {
                    fakeServer.await()
                }
            }
        }
    }

    @Test
    fun `socket runtime client publishes disconnected health on socket drop`() = runBlocking {
        ServerSocket(0).use { server ->
            val fakeServer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        reader.readLine()
                    )
                    delay(100)
                }
            }

            val client = SocketStrategyRuntimeClient(
                host = "127.0.0.1",
                port = server.localPort,
                topics = setOf(StrategyTopic.INTRADAY),
                reconnectDelayMillis = 10_000
            )

            val health = withTimeout(2_000) {
                var current = client.current(StrategyTopic.HEALTH)
                while (current?.payload?.jsonObject?.get("status")?.jsonPrimitive?.content != "DISCONNECTED") {
                    delay(10)
                    current = client.current(StrategyTopic.HEALTH)
                }
                current
            }
            assertEquals("DISCONNECTED", health.payload.jsonObject["status"]?.jsonPrimitive?.content)

            client.close()
            withTimeout(2_000) {
                withContext(Dispatchers.IO) {
                    fakeServer.await()
                }
            }
        }
    }

    @Test
    fun `socket runtime client switches source instance after service restart`() = runBlocking {
        ServerSocket(0).use { server ->
            val fakeServer = async(Dispatchers.IO) {
                listOf("service-a" to 1L, "service-b" to 1L).forEach { (source, version) ->
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                        json.decodeFromString(
                            StrategySocketFrame.serializer(),
                            reader.readLine()
                        )
                        writer.write(
                            json.encodeToString(
                                StrategySocketFrame.serializer(),
                                StrategySocketFrame.Snapshot(
                                    StrategyWireSnapshot(
                                        topic = StrategyTopic.POSITIONS,
                                        version = version,
                                        sourceInstanceId = source,
                                        publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000 + version),
                                        payload = buildJsonObject {
                                            put("source", source)
                                        }
                                    )
                                )
                            )
                        )
                        writer.newLine()
                        writer.flush()
                        delay(100)
                    }
                }
            }

            val client = SocketStrategyRuntimeClient(
                host = "127.0.0.1",
                port = server.localPort,
                topics = setOf(StrategyTopic.POSITIONS),
                reconnectDelayMillis = 50
            )

            withTimeout(2_000) {
                var current = client.current(StrategyTopic.POSITIONS)
                while (current?.sourceInstanceId != "service-a") {
                    delay(10)
                    current = client.current(StrategyTopic.POSITIONS)
                }
            }
            val restarted = withTimeout(2_000) {
                var current = client.current(StrategyTopic.POSITIONS)
                while (current?.sourceInstanceId != "service-b") {
                    delay(10)
                    current = client.current(StrategyTopic.POSITIONS)
                }
                current
            }
            assertEquals(1, restarted.version)
            assertEquals("service-b", restarted.sourceInstanceId)

            client.close()
            withTimeout(2_000) {
                withContext(Dispatchers.IO) {
                    fakeServer.await()
                }
            }
        }
    }

    @Test
    fun `current returns last known snapshot after socket drop`() = runBlocking {
        ServerSocket(0).use { server ->
            val fakeServer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    json.decodeFromString(
                        StrategySocketFrame.serializer(),
                        reader.readLine()
                    )
                    writer.write(
                        json.encodeToString(
                            StrategySocketFrame.serializer(),
                            StrategySocketFrame.Snapshot(
                                StrategyWireSnapshot(
                                    topic = StrategyTopic.POSITIONS,
                                    version = 1,
                                    sourceInstanceId = "service-before-drop",
                                    publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
                                    payload = buildJsonObject {
                                        put("tradeDate", "2026-04-30")
                                        put("source", "before")
                                    }
                                )
                            )
                        )
                    )
                    writer.newLine()
                    writer.flush()
                    delay(100)
                }
            }

            val client = SocketStrategyRuntimeClient(
                host = "127.0.0.1",
                port = server.localPort,
                topics = setOf(StrategyTopic.POSITIONS),
                reconnectDelayMillis = 10_000
            )

            withTimeout(2_000) {
                var current = client.current(StrategyTopic.POSITIONS)
                while (current == null) {
                    delay(10)
                    current = client.current(StrategyTopic.POSITIONS)
                }
            }

            val afterDrop = withTimeout(2_000) {
                var health = client.current(StrategyTopic.HEALTH)
                while (health?.payload?.jsonObject?.get("status")?.jsonPrimitive?.content != "DISCONNECTED") {
                    delay(10)
                    health = client.current(StrategyTopic.HEALTH)
                }
                client.current(StrategyTopic.POSITIONS)
            }
            assertNotNull(afterDrop)
            assertEquals("service-before-drop", afterDrop?.sourceInstanceId)
            assertEquals(1, afterDrop?.version)
            assertEquals("before", afterDrop?.payload?.jsonObject?.get("source")?.jsonPrimitive?.content)

            client.close()
            withTimeout(2_000) {
                withContext(Dispatchers.IO) {
                    fakeServer.await()
                }
            }
        }
    }

    @Test
    fun `observe emits new snapshot with different sourceInstanceId after reconnect`() = runBlocking {
        ServerSocket(0).use { server ->
            val fakeServer = async(Dispatchers.IO) {
                listOf("service-a" to 1L, "service-b" to 2L).forEach { (source, version) ->
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                        json.decodeFromString(
                            StrategySocketFrame.serializer(),
                            reader.readLine()
                        )
                        writer.write(
                            json.encodeToString(
                                StrategySocketFrame.serializer(),
                                StrategySocketFrame.Snapshot(
                                    StrategyWireSnapshot(
                                        topic = StrategyTopic.POSITIONS,
                                        version = version,
                                        sourceInstanceId = source,
                                        publishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000 + version),
                                        payload = buildJsonObject {
                                            put("source", source)
                                        }
                                    )
                                )
                            )
                        )
                        writer.newLine()
                        writer.flush()
                        delay(100)
                    }
                }
            }

            val client = SocketStrategyRuntimeClient(
                host = "127.0.0.1",
                port = server.localPort,
                topics = setOf(StrategyTopic.POSITIONS),
                reconnectDelayMillis = 50
            )

            val snapshots = mutableListOf<String>()
            val job = launch {
                client.observe(StrategyTopic.POSITIONS).collect {
                    snapshots += it.sourceInstanceId
                }
            }

            withTimeout(2_000) {
                while ("service-b" !in snapshots) {
                    delay(10)
                }
            }
            assertEquals(listOf("service-a", "service-b"), snapshots)

            job.cancel()
            client.close()
            withTimeout(2_000) {
                withContext(Dispatchers.IO) {
                    fakeServer.await()
                }
            }
        }
    }
}
