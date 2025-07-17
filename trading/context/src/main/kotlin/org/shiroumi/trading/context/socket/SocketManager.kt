package org.shiroumi.trading.context.socket

import asSingleDispatcher
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import supervisorScope
import java.lang.Thread.sleep
import kotlin.coroutines.CoroutineContext

abstract class SocketManager<T> {

    private val tag = "SocketManager"

    private val selectorManager = SelectorManager(Dispatchers.IO)

    open val exceptionHandlers: List<suspend (CoroutineContext, Throwable) -> Unit> = listOf()

    fun bindToPort(port: Int) = runBlocking {
        val serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", port)
        println("$tag: Server is listening at ${serverSocket.localAddress}")
        serverSocket.startLooper()
    }

    private val combinedCoroutineExceptionHandler: CoroutineContext
        get() {
            var handler: CoroutineContext = CoroutineExceptionHandler { _, throwable ->
                if (throwable is CancellationException) return@CoroutineExceptionHandler
                throwable.printStackTrace()
            }
            exceptionHandlers.forEach {
                handler += CoroutineExceptionHandler { c, t -> runBlocking { it(c, t) } }
            }
            return handler
        }

    /**
     * Prepare for start looper
     *
     * @return a T buffer channel for sending looper
     */
    abstract suspend fun prepare(): Channel<T>

    private fun ServerSocket.startLooper() = runBlocking {
        val job = supervisorScope.launch {
            while (true) {
                val socket = accept().also { println("$tag: Accepted connection from ${it.remoteAddress}") }
                socket.startSendingLooper(buffer = prepare())
                socket.startReceivingLooper()
            }
        }
        while (job.isActive) {
            sleep(1000)
        }
    }

    // send protocol to socket
    private fun AWritable.startSendingLooper(
        buffer: Channel<T>
    ) = supervisorScope.launch {
        val channel = openWriteChannel(autoFlush = true)
        launch(context = "socket_sending_looper".asSingleDispatcher + combinedCoroutineExceptionHandler) {
            for (t in buffer) {
                channel.writeStringUtf8("${onSendData(t)}\n")
            }
        }
    }

    private fun AReadable.startReceivingLooper() = supervisorScope.launch {
        val channel = openReadChannel()
        launch(context = "socket_receiving_looper".asSingleDispatcher + combinedCoroutineExceptionHandler) {
            while (true) {
                onReceiveData(channel.readUTF8Line() ?: continue)
            }
        }
    }

    /**
     * Callback for data coming
     *
     * @param received data received from socket
     */
    abstract suspend fun onReceiveData(received: String)


    /**
     * Callback for data outgoing
     *
     * @param sending data sending to socket
     */
    abstract suspend fun onSendData(sending: T): String
}