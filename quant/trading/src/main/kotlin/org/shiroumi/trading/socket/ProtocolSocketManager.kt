package org.shiroumi.trading.socket

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.shiroumi.protocol.handleProtocol
import org.shiroumi.protocol.serializeProtocol
import org.shiroumi.trading.schedule.Schedular
import org.shiroumi.trading.schedule.SchedularType
import org.shiroumi.trading.schedule.threadLocalSchedular
import protocol.model.Protocol
import threadLocalSendingChannel
import kotlin.concurrent.getOrSet
import kotlin.coroutines.CoroutineContext

/**
 * A socket manager for trading-protocol
 * Other languages like python use this to communicate with server
 */
class ProtocolSocketManager : SocketManager<Protocol>() {

    private val tag: String = "ProtocolSocketManager"

    override val exceptionHandlers: List<suspend (CoroutineContext, Throwable) -> Unit> = listOf { _, _ ->
        threadLocalSendingChannel.get().send(element = status.server.error())
    }

    override suspend fun prepare(): Channel<Protocol> {
        threadLocalSchedular.getOrSet { Schedular(type = SchedularType.Backtesting) }
        return threadLocalSendingChannel.getOrSet { Channel() }
    }

    override suspend fun onReceiveData(received: String) {
//        println("onReceiveData time: ${System.currentTimeMillis()}")
        val (sendingChannel, sendingProtocol) = withContext(context = Dispatchers.socket) {
            threadLocalSendingChannel.get() to handleProtocol(received)
        }
//        println("onReceiveData handled time: ${System.currentTimeMillis()}")
        sendingProtocol?.let { sendingChannel.send(it) }
    }

    override suspend fun onSendData(sending: Protocol): String {
        println("$tag: protocol: ${sending.cmd} ready to write.")
        return "${serializeProtocol(sending)}"
    }

    companion object {
        suspend fun sendProtocol(protocol: Protocol) {
            threadLocalSendingChannel.get().send(protocol)
        }
    }

}