package org.shiroumi.strategy.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategySocketFrame
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.contract.StrategyWireSnapshot
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class SocketStrategySnapshotPublisher(
    private val host: String,
    private val port: Int,
    private val sourceInstanceId: String,
    private val connectTimeoutMillis: Int = 500,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "frameType"
    }
) : AutoCloseable {
    private val lock = Any()
    private val version = AtomicLong(0)
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    suspend fun publish(topic: StrategyTopic, payload: JsonElement): StrategySnapshotEnvelope<JsonElement> =
        withContext(Dispatchers.IO) {
            val envelope = StrategySnapshotEnvelope(
                topic = topic,
                version = version.incrementAndGet(),
                sourceInstanceId = sourceInstanceId,
                publishedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                payload = payload
            )
            val wire = StrategyWireSnapshot(
                topic = envelope.topic,
                version = envelope.version,
                sourceInstanceId = envelope.sourceInstanceId,
                publishedAt = envelope.publishedAt,
                payload = envelope.payload
            )
            val line = json.encodeToString(StrategySocketFrame.serializer(), StrategySocketFrame.Snapshot(wire))
            synchronized(lock) {
                val targetWriter = ensureWriter()
                try {
                    targetWriter.write(line)
                    targetWriter.newLine()
                    targetWriter.flush()
                } catch (error: Exception) {
                    closeLocked()
                    throw error
                }
            }
            envelope
        }

    private fun ensureWriter(): BufferedWriter {
        writer?.let { return it }
        val nextSocket = Socket()
        nextSocket.tcpNoDelay = true
        nextSocket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        socket = nextSocket
        return nextSocket.getOutputStream().bufferedWriter(Charsets.UTF_8).also {
            writer = it
        }
    }

    override fun close() {
        synchronized(lock) {
            closeLocked()
        }
    }

    private fun closeLocked() {
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
    }
}
