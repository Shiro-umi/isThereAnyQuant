package org.shiroumi.trading.context.socket

import Logger
import asSingleDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.shiroumi.protocol.handleProtocol
import org.shiroumi.protocol.serializeProtocol
import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol
import status.server.error
import kotlin.coroutines.CoroutineContext

/**
 * A socket manager for trading-protocol
 * Other languages like python use this to communicate with server
 */
class ProtocolSocketManager(
    val context: Context,
) : SocketManager<Protocol>(), Logger {

    override val className: String = "ProtocolSocketManager"

    private val sendingChannel: Channel<Protocol> = Channel()

    private val Dispatchers.protocolRecv by lazy {
        "socket_protocol_recv".asSingleDispatcher
    }

    private val Dispatchers.protocolSend by lazy {
        "socket_protocol_send".asSingleDispatcher
    }

    override val exceptionHandlers: List<suspend (CoroutineContext, Throwable) -> Unit> = listOf { _, _ ->
        sendingChannel.send(element = error())
    }

    override suspend fun prepare(): Channel<Protocol> {
        return sendingChannel
    }

    override suspend fun onReceiveData(received: String) {
        info("onReceiveData: $received")
        withContext(Dispatchers.protocolRecv) {
            val result = handleProtocol(context = context, protocolJson = received) ?: return@withContext
            sendingChannel.send(element = result)
        }
    }

    override suspend fun onSendData(sending: Protocol): String {
        info("protocol: ${serializeProtocol(sending)} ready to write.")
        return "${serializeProtocol(sending)}"
    }

    suspend fun sendProtocol(sending: Protocol) {
        info("send protocol: $sending")
        sendingChannel.send(element = sending)
    }
}