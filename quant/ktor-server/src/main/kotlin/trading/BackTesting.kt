package org.shiroumi.trading

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.read
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.shiroumi.supervisorScope
import org.shiroumi.trading.strategy.AbsStrategy
import org.shiroumi.trading.strategy.PyStrategy

/**
 * The backtesting util
 *
 * @param fileName name of side-load script
 */
class BackTesting(val fileName: String) {

    private val socketPort: Int by lazy {
        (6000 until 6500).random()
    }

    val trader: Trader by lazy { Trader() }

    private var socket: Socket? = null

    val strategy: AbsStrategy by lazy {
        PyStrategy(fileName = fileName, port = socketPort)
    }

    fun initialize() {
        initSocket()
    }

    private fun initSocket() = runBlocking {
        val selectorManager = SelectorManager(Dispatchers.IO)
        val serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", socketPort)
        println("Server is listening at ${serverSocket.localAddress}")
        while (true) {
            val socket = serverSocket.accept()
            println("Accepted $socket")
            launch {
                val receiveChannel = socket.openReadChannel()
                val sendChannel = socket.openWriteChannel(autoFlush = true)
                sendChannel.writeStringUtf8("Please enter your name\n")
                try {
                    while (true) {
                        val name = receiveChannel.readUTF8Line()
                        sendChannel.writeStringUtf8("Hello, $name!\n")
                    }
                } catch (e: Throwable) {
                    socket.close()
                }
            }
        }
    }
}