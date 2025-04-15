package org.shiroumi.trading.context.socket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.shiroumi.protocol.handleProtocol
import org.shiroumi.protocol.serializeProtocol
import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol
import org.shiroumi.trading.context.protocol.threadLocalSendingChannel
import status.server.error
import kotlin.concurrent.getOrSet
import kotlin.coroutines.CoroutineContext

/**
 * A socket manager for trading-protocol
 * Other languages like python use this to communicate with server
 */
class ProtocolSocketManager(
    val context: Context,
) : SocketManager<Protocol>() {

    private val tag: String = "ProtocolSocketManager"

    override val exceptionHandlers: List<suspend (CoroutineContext, Throwable) -> Unit> = listOf { _, _ ->
        threadLocalSendingChannel.get().send(element = error())
    }

    override suspend fun prepare(): Channel<Protocol> {
        return threadLocalSendingChannel.getOrSet { Channel() }
    }

    override suspend fun onReceiveData(received: String) {
        val (sendingChannel, sendingProtocol) = withContext(context = Dispatchers.socket) {
            threadLocalSendingChannel.get() to handleProtocol(context = context, protocolJson = received)
        }
        sendingProtocol?.let { sendingChannel.send(it) }
    }

    override suspend fun onSendData(sending: Protocol): String {
        println("$tag: protocol: ${sending.cmd} ready to write.")
        return "${serializeProtocol(sending)}"
    }

    suspend fun sendProtocol(sending: Protocol) = withContext(context = Dispatchers.socket) {
        threadLocalSendingChannel.get().send(element = sending)
    }
}