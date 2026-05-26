package org.shiroumi.strategy.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.shiroumi.strategy.contract.STRATEGY_CONTRACT_VERSION
import org.shiroumi.strategy.contract.StrategyCommand
import org.shiroumi.strategy.contract.StrategyCommandAck
import org.shiroumi.strategy.contract.StrategyRuntimeClient
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategySocketFrame
import org.shiroumi.strategy.contract.StrategyTopic
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SocketStrategyRuntimeClient(
    private val host: String,
    private val port: Int,
    private val topics: Set<StrategyTopic>,
    private val commandHandler: (suspend (StrategyCommand) -> StrategyCommandAck)? = null,
    private val connectTimeoutMillis: Int = 500,
    private val commandTimeoutMillis: Long = 60_000,
    private val reconnectDelayMillis: Long = 1_000,
    private val compatibleContractVersions: Set<Int> = setOf(STRATEGY_CONTRACT_VERSION),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "frameType"
    }
) : StrategyRuntimeClient<JsonElement>, AutoCloseable {
    private val hub = LocalStrategySnapshotHub<JsonElement>(
        sourceInstanceId = "socket-client-${UUID.randomUUID()}"
    )
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<StrategyCommandAck>>()
    private val writeLock = Any()
    private var writer: BufferedWriter? = null
    private val connectionJob: Job = scope.launch {
        connectionLoop()
    }

    override suspend fun current(topic: StrategyTopic): StrategySnapshotEnvelope<JsonElement>? =
        hub.current(topic)

    override fun observe(topic: StrategyTopic): Flow<StrategySnapshotEnvelope<JsonElement>> =
        hub.observe(topic)

    override suspend fun send(command: StrategyCommand): StrategyCommandAck {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<StrategyCommandAck>()
        pendingAcks[requestId] = deferred
        val sent = writeFrame(
            StrategySocketFrame.Command(
                requestId = requestId,
                contractVersion = STRATEGY_CONTRACT_VERSION,
                command = command
            )
        )
        if (!sent) {
            pendingAcks.remove(requestId)
            return StrategyCommandAck(
                accepted = false,
                message = "strategy-service socket is not connected"
            )
        }
        return withTimeoutOrNull(commandTimeoutMillis) {
            deferred.await()
        } ?: StrategyCommandAck(
            accepted = false,
            message = "strategy-service command timed out"
        ).also {
            pendingAcks.remove(requestId)
        }
    }

    override fun close() {
        connectionJob.cancel()
        scope.cancel()
        clearWriter()
        failPending("strategy socket client closed")
    }

    private suspend fun connectionLoop() {
        while (scope.isActive) {
            val result = runCatching {
                connectAndRead()
            }
            publishHealth(
                status = "DISCONNECTED",
                message = result.exceptionOrNull()?.message ?: "strategy-service socket disconnected"
            )
            clearWriter()
            failPending("strategy-service socket disconnected")
            delay(reconnectDelayMillis)
        }
    }

    private suspend fun connectAndRead() = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            val nextWriter = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            synchronized(writeLock) {
                writer = nextWriter
            }
            publishHealth(status = "CONNECTED", message = "strategy-service socket connected")
            writeFrame(
                StrategySocketFrame.Subscribe(
                    requestId = UUID.randomUUID().toString(),
                    contractVersion = STRATEGY_CONTRACT_VERSION,
                    topics = topics
                )
            )
            socket.getInputStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    handleFrame(json.decodeFromString(StrategySocketFrame.serializer(), line))
                }
            }
        }
    }

    private suspend fun handleFrame(frame: StrategySocketFrame) {
        when (frame) {
            is StrategySocketFrame.Snapshot -> {
                val wire = frame.snapshot
                if (wire.contractVersion !in compatibleContractVersions) {
                    hub.publish(
                        StrategyTopic.HEALTH,
                        buildJsonObject {
                            put("status", "CONTRACT_VERSION_MISMATCH")
                            put("expectedContractVersion", STRATEGY_CONTRACT_VERSION)
                            put("actualContractVersion", wire.contractVersion)
                            put("lastTopic", wire.topic.name)
                            put("lastSourceInstanceId", wire.sourceInstanceId)
                        }
                    )
                    return
                }
                hub.publishEnvelope(
                    StrategySnapshotEnvelope(
                        topic = wire.topic,
                        version = wire.version,
                        sourceInstanceId = wire.sourceInstanceId,
                        publishedAt = wire.publishedAt,
                        payload = wire.payload
                    )
                )
            }
            is StrategySocketFrame.Ack -> {
                val ack = if (frame.ack.contractVersion in compatibleContractVersions) {
                    frame.ack
                } else {
                    frame.ack.copy(
                        accepted = false,
                        message = "strategy-service contract version mismatch: expected=$STRATEGY_CONTRACT_VERSION actual=${frame.ack.contractVersion}"
                    )
                }
                pendingAcks.remove(frame.requestId)?.complete(ack)
            }
            is StrategySocketFrame.Command -> {
                val ack = if (frame.contractVersion in compatibleContractVersions) {
                    runCatching {
                        commandHandler?.invoke(frame.command) ?: StrategyCommandAck(
                            accepted = false,
                            message = "socket client command handler is not configured"
                        )
                    }.getOrElse { error ->
                        StrategyCommandAck(
                            accepted = false,
                            message = "socket client command handler failed: ${error.message}"
                        )
                    }
                } else {
                    StrategyCommandAck(
                        accepted = false,
                        message = "strategy-service contract version mismatch: expected=$STRATEGY_CONTRACT_VERSION actual=${frame.contractVersion}"
                    )
                }
                writeFrame(StrategySocketFrame.Ack(requestId = frame.requestId, ack = ack))
            }
            is StrategySocketFrame.Subscribe -> Unit
        }
    }

    private fun writeFrame(frame: StrategySocketFrame): Boolean {
        val line = json.encodeToString(StrategySocketFrame.serializer(), frame)
        return synchronized(writeLock) {
            val targetWriter = writer ?: return@synchronized false
            runCatching {
                targetWriter.write(line)
                targetWriter.newLine()
                targetWriter.flush()
            }.isSuccess
        }
    }

    private fun clearWriter() {
        synchronized(writeLock) {
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun failPending(message: String) {
        val ack = StrategyCommandAck(accepted = false, message = message)
        pendingAcks.entries.forEach { entry ->
            if (pendingAcks.remove(entry.key, entry.value)) {
                entry.value.complete(ack)
            }
        }
    }

    private suspend fun publishHealth(status: String, message: String) {
        hub.publish(
            StrategyTopic.HEALTH,
            buildJsonObject {
                put("status", status)
                put("source", "socket-strategy-runtime-client")
                put("host", host)
                put("port", port)
                put("message", message)
                put("updatedAtEpochMillis", System.currentTimeMillis())
            }
        )
    }
}
