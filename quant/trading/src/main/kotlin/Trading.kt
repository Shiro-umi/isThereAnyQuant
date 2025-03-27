package org.shiroumi.trading

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.shiroumi.Trader
import org.shiroumi.protocol.ProtocolDecoder
import org.shiroumi.protocol.handleProtocol
import protocol.model.Protocol
import strategy.AbsStrategy
import strategy.PyStrategy

/**
 * The backtesting util
 *
 * @param fileName name of side-load script
 */
class Trading(
    val scriptName: String
) {

    sealed class Type(val name: String) {
        object Trading : Type("trading")
        object Backtesting : Type("backtesting")
    }

    private val socketPort: Int by lazy {
        (6000 until 6500).random()
    }

    val trader: Trader by lazy { Trader() }

    val strategy: AbsStrategy by lazy {
        PyStrategy(fileName = scriptName, port = socketPort)
    }

    fun initialize(type: Type) {
        println("Trading initializing as type: ${type.name}")
        initSocket()
    }

    private fun initSocket() = runBlocking {
        val selectorManager = SelectorManager(Dispatchers.IO)
        val serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", socketPort)
        println("Server is listening at ${serverSocket.localAddress}")
        while (true) {
            val socket = serverSocket.accept()
            launch {
                val receiveChannel = socket.openReadChannel()
                val sendChannel = socket.openWriteChannel(autoFlush = true)
                sendChannel.writeStringUtf8("Server started listening\n")
                try {
                    while (true) {
                        val input = receiveChannel.readUTF8Line()?.also {
                            println("received protocol $it from ${socket.remoteAddress}")
                        } ?: continue
                        // handle received protocol
                        val protocol = handleProtocol(ProtocolDecoder.decodeFromString<Protocol>(input))
                        // send protocol result
                        sendChannel.writeStringUtf8("${ProtocolDecoder.encodeToString(protocol)}\n")
                    }
                } catch (e: Throwable) {
                    socket.close()
                }
            }
        }
    }
}