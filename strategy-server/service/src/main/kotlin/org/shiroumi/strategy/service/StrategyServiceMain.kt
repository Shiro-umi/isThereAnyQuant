package org.shiroumi.strategy.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import model.Candle
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.config.ConfigManager
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.STRATEGY_CONTRACT_VERSION
import org.shiroumi.strategy.contract.StrategyCommand
import org.shiroumi.strategy.contract.StrategyCommandAck
import org.shiroumi.strategy.contract.StrategySnapshotEnvelope
import org.shiroumi.strategy.contract.StrategySocketFrame
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.contract.StrategyWireSnapshot
import org.shiroumi.strategy.service.runtime.DefaultIntradayStrategyRuntimeDataSource
import org.shiroumi.strategy.service.runtime.IntradayStrategyRuntime
import org.shiroumi.strategy.service.runtime.KtorRealtimeDailyCandleClient
import org.shiroumi.strategy.service.runtime.KtorSnapshotStrategyRealtimeDailyFactSource
import org.shiroumi.strategy.service.runtime.PostMarketStrategyRuntime
import org.shiroumi.strategy.service.runtime.StrategyPositionTrackingRuntime
import utils.logger
import java.io.BufferedWriter
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger by logger("StrategyService")

internal val SERVICE_OWNED_TOPICS: Set<StrategyTopic> = setOf(
    StrategyTopic.INTRADAY,
    StrategyTopic.POSITIONS,
    StrategyTopic.POSITION_TRACKING
)

fun main() = runBlocking {
    val config = ConfigManager.load()
    val host = System.getenv("STRATEGY_SOCKET_BIND_HOST")
        ?: config.strategy.strategyServiceBindHost
    val port = System.getenv("STRATEGY_SOCKET_PORT")?.toIntOrNull()
        ?: config.strategy.strategyServicePort
    val service = StrategySocketService(host = host, port = port)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            service.close()
        }
    )
    service.run()
}

private class StrategySocketService(
    private val host: String,
    private val port: Int,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "frameType"
    }
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceInstanceId = "strategy-service-${UUID.randomUUID()}"
    private val snapshotHub = LocalStrategySnapshotHub<JsonElement>(
        sourceInstanceId = serviceInstanceId,
        payloadVersionKey = ::strategyPayloadVersionKey
    )
    private val positionTrackingRuntime = StrategyPositionTrackingRuntime(
        snapshotHub = snapshotHub,
        json = json
    )
    private val pendingKtorAcks = ConcurrentHashMap<String, CompletableDeferred<StrategyCommandAck>>()
    private val ktorSessions = ConcurrentHashMap.newKeySet<StrategySocketSession>()
    private val ktorRealtimeClient = SocketKtorRealtimeDailyCandleClient(
        json = json,
        sendCommand = ::sendCommandToKtor
    )
    private val intradayRuntime = IntradayStrategyRuntime(
        snapshotHub = snapshotHub,
        json = json,
        dataSource = DefaultIntradayStrategyRuntimeDataSource(
            realtimeFactSource = KtorSnapshotStrategyRealtimeDailyFactSource(ktorRealtimeClient)
        ),
        trackingRuntime = positionTrackingRuntime
    )
    private val postMarketRuntime = PostMarketStrategyRuntime(
        snapshotHub = snapshotHub,
        json = json,
        trackingRuntime = positionTrackingRuntime
    )
    private val commandHandler = StrategyCommandHandler(
        serviceInstanceId = serviceInstanceId,
        intradayRuntime = intradayRuntime,
        postMarketRuntime = postMarketRuntime,
        snapshotHub = snapshotHub,
        json = json
    )
    private var serverSocket: ServerSocket? = null

    suspend fun run() {
        ServerSocket(port, 50, java.net.InetAddress.getByName(host)).use { server ->
            serverSocket = server
            logger.info("strategy-service socket listening host=$host port=$port")
            publishHealth(status = "STARTED")
            startLatestPositionSnapshotHydration()
            startIntradayRuntimeLoop()
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                socket.tcpNoDelay = true
                scope.launch {
                    handleConnection(socket)
                }
            }
        }
    }

    private fun startLatestPositionSnapshotHydration() {
        scope.launch {
            runCatching {
                postMarketRuntime.publishLatestPositions("startup-hydration")
            }.onSuccess { result ->
                publishHealth(
                    status = if (result.accepted) {
                        "POSITIONS_HYDRATED"
                    } else {
                        "POSITIONS_HYDRATION_SKIPPED"
                    },
                    topic = StrategyTopic.POSITIONS,
                    version = result.positionsEnvelope?.version,
                    sourceInstanceId = result.positionsEnvelope?.sourceInstanceId
                )
            }.onFailure { error ->
                logger.warning("strategy-service latest positions hydration failed: ${error.message}")
                publishHealth(status = "POSITIONS_HYDRATION_FAILED", topic = StrategyTopic.POSITIONS)
            }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.use { client ->
            val remote = client.remoteSocketAddress.toString()
            val session = StrategySocketSession(
                remote = remote,
                writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)
            )
            logger.info("strategy-service socket connected remote=$remote")
            try {
                client.getInputStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        runCatching {
                            handleFrame(
                                session = session,
                                frame = json.decodeFromString(StrategySocketFrame.serializer(), line)
                            )
                        }.onFailure { error ->
                            logger.warning("strategy-service frame rejected remote=$remote: ${error.message}")
                        }
                    }
                }
            } finally {
                ktorSessions -= session
                session.close()
                logger.info("strategy-service socket disconnected remote=$remote")
            }
        }
    }

    private suspend fun handleFrame(session: StrategySocketSession, frame: StrategySocketFrame) {
        when (frame) {
            is StrategySocketFrame.Subscribe -> {
                if (!isCompatible(frame.contractVersion)) {
                    session.sendAck(
                        frame.requestId,
                        incompatibleAck(frame.contractVersion)
                    )
                    publishHealth(
                        status = "CONTRACT_VERSION_MISMATCH",
                        actualContractVersion = frame.contractVersion
                    )
                    return
                }
                session.replaceSubscriptions(frame.topics)
                ktorSessions += session
                frame.topics.forEach { topic ->
                    snapshotHub.current(topic)?.let { envelope ->
                        session.sendSnapshot(envelope)
                    }
                    session.addJob(
                        scope.launch {
                            snapshotHub.observe(topic).collect { envelope ->
                                session.sendSnapshot(envelope)
                            }
                        }
                    )
                }
                session.sendAck(
                    frame.requestId,
                    StrategyCommandAck(
                        accepted = true,
                        message = "subscribed ${frame.topics.joinToString()}",
                        sourceInstanceId = serviceInstanceId
                    )
                )
            }
            is StrategySocketFrame.Snapshot -> {
                val wire = frame.snapshot
                if (!isCompatible(wire.contractVersion)) {
                    logger.warning(
                        "strategy-service rejected incompatible snapshot topic=${wire.topic} " +
                            "contractVersion=${wire.contractVersion}"
                    )
                    publishHealth(
                        status = "CONTRACT_VERSION_MISMATCH",
                        topic = wire.topic,
                        sourceInstanceId = wire.sourceInstanceId,
                        actualContractVersion = wire.contractVersion
                    )
                    return
                }
                if (wire.topic in SERVICE_OWNED_TOPICS) {
                    logger.info(
                        "strategy-service ignored external snapshot for owned topic=${wire.topic} " +
                            "version=${wire.version} source=${wire.sourceInstanceId}"
                    )
                    publishHealth(
                        status = "SNAPSHOT_IGNORED_OWNED_TOPIC",
                        topic = wire.topic,
                        version = wire.version,
                        sourceInstanceId = wire.sourceInstanceId
                    )
                    return
                }
                val envelope = StrategySnapshotEnvelope(
                    topic = wire.topic,
                    version = wire.version,
                    sourceInstanceId = wire.sourceInstanceId,
                    publishedAt = wire.publishedAt,
                    payload = wire.payload
                )
                snapshotHub.publishEnvelope(envelope)
                logger.info(
                    "strategy-service snapshot accepted topic=${wire.topic} " +
                        "version=${wire.version} source=${wire.sourceInstanceId}"
                )
                publishHealth(
                    status = "SNAPSHOT_ACCEPTED",
                    topic = wire.topic,
                    version = wire.version,
                    sourceInstanceId = wire.sourceInstanceId
                )
            }
            is StrategySocketFrame.Command -> {
                if (!isCompatible(frame.contractVersion)) {
                    session.sendAck(frame.requestId, incompatibleAck(frame.contractVersion))
                    publishHealth(
                        status = "CONTRACT_VERSION_MISMATCH",
                        actualContractVersion = frame.contractVersion
                    )
                    return
                }
                val ack = commandHandler.handle(frame.command)
                session.sendAck(frame.requestId, ack)
                logger.info(
                    "strategy-service command received requestId=${frame.requestId} " +
                        "accepted=${ack.accepted}"
                )
            }
            is StrategySocketFrame.Ack -> {
                pendingKtorAcks.remove(frame.requestId)?.complete(frame.ack)
                    ?: logger.info("strategy-service ack received requestId=${frame.requestId} accepted=${frame.ack.accepted}")
            }
        }
    }

    private suspend fun sendCommandToKtor(command: StrategyCommand): StrategyCommandAck {
        val session = ktorSessions.firstOrNull()
            ?: return StrategyCommandAck(
                accepted = false,
                message = "ktor-server socket session is not connected",
                sourceInstanceId = serviceInstanceId
            )
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<StrategyCommandAck>()
        pendingKtorAcks[requestId] = deferred
        val sent = session.sendCommand(requestId, command)
        if (!sent) {
            pendingKtorAcks.remove(requestId)
            return StrategyCommandAck(
                accepted = false,
                message = "failed to write command to ktor-server socket",
                sourceInstanceId = serviceInstanceId
            )
        }
        return withTimeoutOrNull(5_000L) {
            deferred.await()
        } ?: StrategyCommandAck(
            accepted = false,
            message = "ktor-server realtime candle request timed out",
            sourceInstanceId = serviceInstanceId
        ).also {
            pendingKtorAcks.remove(requestId)
        }
    }

    private fun startIntradayRuntimeLoop() {
        val intervalMillis = System.getenv("STRATEGY_INTRADAY_REFRESH_INTERVAL_MS")
            ?.toLongOrNull()
            ?.coerceAtLeast(5_000L)
            ?: 30 * 60_000L
        val idleIntervalMillis = System.getenv("STRATEGY_INTRADAY_IDLE_CHECK_INTERVAL_MS")
            ?.toLongOrNull()
            ?.coerceAtLeast(5_000L)
            ?: 60_000L
        val schedule = IntradayRuntimeSchedule()
        scope.launch {
            delay(500L)
            var lastSkippedPhase: IntradayRuntimeSchedule.Phase? = null
            while (isActive) {
                val decision = schedule.currentDecision()
                if (decision.shouldRefresh) {
                    lastSkippedPhase = null
                    runCatching {
                        val result = intradayRuntime.refresh("runtime-loop:${decision.phase.name}")
                        publishHealth(
                            status = if (result.accepted) "INTRADAY_REFRESHED" else "INTRADAY_REFRESH_SKIPPED",
                            topic = StrategyTopic.INTRADAY,
                            version = result.intradayEnvelope?.version,
                            sourceInstanceId = result.intradayEnvelope?.sourceInstanceId
                        )
                    }.onFailure { error ->
                        logger.warning("strategy-service intraday runtime refresh failed: ${error.message}")
                        publishHealth(status = "INTRADAY_REFRESH_FAILED", topic = StrategyTopic.INTRADAY)
                    }
                } else if (lastSkippedPhase != decision.phase) {
                    lastSkippedPhase = decision.phase
                    logger.info("strategy-service intraday runtime paused phase=${decision.phase}")
                    publishHealth(status = "INTRADAY_REFRESH_PAUSED_${decision.phase.name}", topic = StrategyTopic.INTRADAY)
                }
                delay(if (decision.phase == IntradayRuntimeSchedule.Phase.TRADING_ACTIVE) intervalMillis else idleIntervalMillis)
            }
        }
    }

    private suspend fun publishHealth(
        status: String,
        topic: StrategyTopic? = null,
        version: Long? = null,
        sourceInstanceId: String? = null,
        actualContractVersion: Int? = null
    ) {
        snapshotHub.publish(
            StrategyTopic.HEALTH,
            buildJsonObject {
                put("status", status)
                put("serviceInstanceId", serviceInstanceId)
                put("contractVersion", STRATEGY_CONTRACT_VERSION)
                topic?.let { put("lastTopic", it.name) }
                version?.let { put("lastVersion", it) }
                sourceInstanceId?.let { put("lastSourceInstanceId", it) }
                actualContractVersion?.let { put("actualContractVersion", it) }
                put("updatedAtEpochMillis", System.currentTimeMillis())
            }
        )
    }

    private fun isCompatible(contractVersion: Int): Boolean =
        contractVersion == STRATEGY_CONTRACT_VERSION

    private fun incompatibleAck(actualContractVersion: Int): StrategyCommandAck =
        StrategyCommandAck(
            accepted = false,
            message = "strategy-service contract version mismatch: expected=$STRATEGY_CONTRACT_VERSION actual=$actualContractVersion",
            sourceInstanceId = serviceInstanceId,
            contractVersion = STRATEGY_CONTRACT_VERSION
        )

    override fun close() {
        runCatching { serverSocket?.close() }
        scope.cancel()
    }

    private inner class StrategySocketSession(
        private val remote: String,
        private val writer: BufferedWriter
    ) : AutoCloseable {
        private val writeLock = Any()
        private val jobs = mutableListOf<Job>()

        fun replaceSubscriptions(topics: Set<StrategyTopic>) {
            jobs.forEach { it.cancel() }
            jobs.clear()
            logger.info("strategy-service subscribed remote=$remote topics=${topics.joinToString()}")
        }

        fun addJob(job: Job) {
            jobs += job
        }

        fun sendAck(requestId: String, ack: StrategyCommandAck) {
            sendFrame(StrategySocketFrame.Ack(requestId = requestId, ack = ack))
        }

        fun sendCommand(requestId: String, command: StrategyCommand): Boolean =
            sendFrame(
                StrategySocketFrame.Command(
                    requestId = requestId,
                    contractVersion = STRATEGY_CONTRACT_VERSION,
                    command = command
                )
            )

        fun sendSnapshot(envelope: StrategySnapshotEnvelope<JsonElement>) {
            sendFrame(
                StrategySocketFrame.Snapshot(
                    StrategyWireSnapshot(
                        topic = envelope.topic,
                        version = envelope.version,
                        sourceInstanceId = envelope.sourceInstanceId,
                        publishedAt = envelope.publishedAt,
                        payload = envelope.payload
                    )
                )
            )
        }

        private fun sendFrame(frame: StrategySocketFrame): Boolean {
            val line = json.encodeToString(StrategySocketFrame.serializer(), frame)
            return synchronized(writeLock) {
                runCatching {
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                }.isSuccess
            }
        }

        override fun close() {
            jobs.forEach { it.cancel() }
            jobs.clear()
            runCatching { writer.close() }
        }
    }
}

private class SocketKtorRealtimeDailyCandleClient(
    private val json: Json,
    private val sendCommand: suspend (StrategyCommand) -> StrategyCommandAck
) : KtorRealtimeDailyCandleClient {
    override suspend fun loadRealtimeDailyCandles(
        tsCodes: List<String>,
        tradeDate: kotlinx.datetime.LocalDate
    ): List<Candle> {
        val ack = sendCommand(
            StrategyCommand.LoadRealtimeDailyCandles(
                tsCodes = tsCodes,
                tradeDate = tradeDate.toString()
            )
        )
        if (!ack.accepted) return emptyList()
        val payload = ack.payload ?: return emptyList()
        return runCatching {
            json.decodeFromJsonElement(ListSerializer(Candle.serializer()), payload)
        }.getOrDefault(emptyList())
    }
}

private fun strategyPayloadVersionKey(payload: JsonElement): JsonElement {
    if (payload !is JsonObject) return payload
    val ignoredKeys = setOf("timestamp", "calcMetrics", "updatedAtEpochMillis")
    return JsonObject(payload.filterKeys { it !in ignoredKeys })
}

internal class IntradayRuntimeSchedule(
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Shanghai")),
    private val refreshOutsideTradingSession: Boolean = System.getenv("STRATEGY_INTRADAY_REFRESH_OUTSIDE_SESSION_ENABLED")
        ?.equals("true", ignoreCase = true) == true,
    private val tradingDateResolver: (kotlinx.datetime.LocalDate, DayOfWeek) -> Boolean =
        { date, dayOfWeek -> defaultTradingDateResolver(date, dayOfWeek) }
) {
    enum class Phase {
        TRADING_ACTIVE,
        TRADING_BREAK,
        OFF_MARKET,
        CLOSED_DAY
    }

    data class Decision(
        val phase: Phase,
        val shouldRefresh: Boolean
    )

    private val morningOpen = LocalTime.of(9, 30)
    private val morningClose = LocalTime.of(11, 30)
    private val afternoonOpen = LocalTime.of(13, 0)
    private val afternoonClose = LocalTime.of(15, 0)

    fun currentDecision(): Decision {
        val now = java.time.LocalDateTime.now(clock)
        val tradeDate = kotlinx.datetime.LocalDate(now.year, now.monthValue, now.dayOfMonth)
        if (!tradingDateResolver(tradeDate, now.dayOfWeek)) {
            return Decision(Phase.CLOSED_DAY, refreshOutsideTradingSession)
        }

        val time = now.toLocalTime()
        val phase = when {
            time.inRange(morningOpen, morningClose) || time.inRange(afternoonOpen, afternoonClose) ->
                Phase.TRADING_ACTIVE
            time.inRange(morningClose, afternoonOpen) -> Phase.TRADING_BREAK
            else -> Phase.OFF_MARKET
        }
        return Decision(
            phase = phase,
            shouldRefresh = phase == Phase.TRADING_ACTIVE || refreshOutsideTradingSession
        )
    }

    private fun LocalTime.inRange(startInclusive: LocalTime, endExclusive: LocalTime): Boolean =
        !isBefore(startInclusive) && isBefore(endExclusive)

    companion object {
        private fun defaultTradingDateResolver(
            date: kotlinx.datetime.LocalDate,
            dayOfWeek: DayOfWeek
        ): Boolean {
            val latestTradingDate = runCatching {
                TradingCalendarRepository.findLatestTradingDateOnOrBefore(date)
            }.getOrNull()
            return latestTradingDate == date ||
                (latestTradingDate == null && dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY)
        }
    }
}
