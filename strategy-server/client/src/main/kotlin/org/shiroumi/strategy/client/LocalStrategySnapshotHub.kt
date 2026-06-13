package org.shiroumi.strategy.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.datetime.Instant
import org.shiroumi.strategy.contract.StrategyCommand
import org.shiroumi.strategy.contract.StrategyCommandAck
import org.shiroumi.strategy.contract.StrategyRuntimeClient
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategySnapshotSink
import org.shiroumi.strategy.contract.StrategyTopic
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class LocalStrategySnapshotHub<T>(
    private val sourceInstanceId: String = "local-${UUID.randomUUID()}",
    private val payloadVersionKey: (T) -> Any? = { it }
) : StrategyRuntimeClient<T>, StrategySnapshotSink<T> {
    private val versions = ConcurrentHashMap<StrategyTopic, AtomicLong>()
    private val versionKeys = ConcurrentHashMap<StrategyTopic, Any>()
    private val current = ConcurrentHashMap<StrategyTopic, StrategySnapshotEnvelope<T>>()
    private val updates = MutableSharedFlow<StrategySnapshotEnvelope<T>>(replay = 0, extraBufferCapacity = 64)

    override suspend fun current(topic: StrategyTopic): StrategySnapshotEnvelope<T>? = current[topic]

    override fun observe(topic: StrategyTopic): Flow<StrategySnapshotEnvelope<T>> =
        updates.filter { it.topic == topic }

    override suspend fun publish(topic: StrategyTopic, payload: T): StrategySnapshotEnvelope<T> {
        val nextVersionKey = payloadVersionKey(payload) ?: NULL_VERSION_KEY
        current[topic]
            ?.takeIf { versionKeys[topic] == nextVersionKey }
            ?.let { return it }

        val version = versions.computeIfAbsent(topic) { AtomicLong(0) }.incrementAndGet()
        val envelope = StrategySnapshotEnvelope(
            topic = topic,
            version = version,
            sourceInstanceId = sourceInstanceId,
            publishedAt = Instant.fromEpochMilliseconds(java.lang.System.currentTimeMillis()),
            payload = payload
        )
        versionKeys[topic] = nextVersionKey
        current[topic] = envelope
        updates.emit(envelope)
        return envelope
    }

    suspend fun publishEnvelope(envelope: StrategySnapshotEnvelope<T>): StrategySnapshotEnvelope<T> {
        versions.computeIfAbsent(envelope.topic) { AtomicLong(0) }
            .updateAndGet { current -> maxOf(current, envelope.version) }
        versionKeys[envelope.topic] = payloadVersionKey(envelope.payload) ?: NULL_VERSION_KEY
        current[envelope.topic] = envelope
        updates.emit(envelope)
        return envelope
    }

    override suspend fun send(command: StrategyCommand): StrategyCommandAck {
        return when (command) {
            StrategyCommand.HealthCheck -> StrategyCommandAck(
                accepted = true,
                message = "local strategy runtime available",
                sourceInstanceId = sourceInstanceId
            )
            is StrategyCommand.RefreshIntraday -> StrategyCommandAck(
                accepted = false,
                message = "local strategy runtime does not own refresh orchestration yet",
                sourceInstanceId = sourceInstanceId
            )
            is StrategyCommand.RebuildDate -> StrategyCommandAck(
                accepted = false,
                message = "local strategy runtime does not own post-market rebuild yet",
                sourceInstanceId = sourceInstanceId
            )
            is StrategyCommand.RebuildRange -> StrategyCommandAck(
                accepted = false,
                message = "local strategy runtime does not own post-market rebuild yet",
                sourceInstanceId = sourceInstanceId
            )
            is StrategyCommand.LoadRealtimeDailyCandles -> StrategyCommandAck(
                accepted = false,
                message = "local strategy runtime cannot read ktor candle snapshots",
                sourceInstanceId = sourceInstanceId
            )
            is StrategyCommand.BuildCalibratedTracking -> StrategyCommandAck(
                accepted = false,
                message = "local strategy runtime does not own calibrated tracking replay",
                sourceInstanceId = sourceInstanceId
            )
        }
    }

    private companion object {
        private val NULL_VERSION_KEY = Any()
    }
}
