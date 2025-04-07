import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.shiroumi.protocol.handleProtocol
import org.shiroumi.protocol.serializeProtocol
import org.shiroumi.trading.schedule.Schedular
import org.shiroumi.trading.schedule.SchedularType
import org.shiroumi.trading.schedule.threadLocalSchedular

class SocketManager {
    val selectorManager = SelectorManager(Dispatchers.IO)
    var serverSocket: ServerSocket? = null

    fun bindToPort(port: Int) = runBlocking {
        serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", port)
        val socket = serverSocket ?: return@runBlocking
        println("Server is listening at ${socket.localAddress}")
        observeConnection()
    }

    private suspend fun observeConnection() = supervisorScope {
        while (true) {
            val socket = serverSocket?.accept() ?: break
            println("Accepted ${socket.remoteAddress}")
            launch(Dispatchers.IO + CoroutineName("socket_handler_${socket}")) {
                threadLocalSchedular.set(Schedular(SchedularType.Backtesting))
                val receiveChannel = socket.openReadChannel()
                val sendChannel = socket.openWriteChannel(autoFlush = true)
                println("send to client startup event")
                try {
                    while (true) {
                        val data = receiveChannel.readUTF8Line() ?: continue
                        println("Received $data")
                        // handle received protocol
                        val resultProtocol = handleProtocol(data)
                        if (resultProtocol == null) continue
                        // send protocol result
                        sendChannel.writeStringUtf8("${serializeProtocol(resultProtocol)}\n")
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    sendChannel.writeStringUtf8("error! \n")
                }
            }
        }
    }
}