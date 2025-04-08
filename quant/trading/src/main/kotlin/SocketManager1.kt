import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.shiroumi.protocol.handleProtocol
import org.shiroumi.protocol.serializeProtocol
import org.shiroumi.trading.schedule.Schedular
import org.shiroumi.trading.schedule.SchedularType
import org.shiroumi.trading.schedule.threadLocalSchedular
import java.util.concurrent.Executors
import kotlin.concurrent.getOrSet

class SocketManager1 {

    private val socketDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "socket_looper").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val selectorManager = SelectorManager(Dispatchers.IO)

    private var serverSocket: ServerSocket? = null

    fun bindToPort(port: Int) = runBlocking {
        serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", port)
        val socket = serverSocket ?: return@runBlocking
        println("Server is listening at ${socket.localAddress}")
        startLooper()
    }

    private suspend fun startLooper() = supervisorScope {
        while (true) {
            val socket = serverSocket?.accept() ?: break
            println("Accepted ${socket.remoteAddress}")
            launch(socketDispatcher) {
                threadLocalSchedular.set(Schedular(SchedularType.Backtesting))
//                threadLocalSendingChannel.set(MutableStateFlow(null))
                val receiveChannel = socket.openReadChannel()
                val sendChannel = socket.openWriteChannel(autoFlush = true)
//                launch { sendLooper(sendChannel) }
                launch(Dispatchers.IO) { replyLooper(receiveChannel, sendChannel) }
            }
        }
    }

    // receive then reply
    private suspend fun replyLooper(
        receiveChannel: ByteReadChannel, sendChannel: ByteWriteChannel
    ) {
        try {
            while (true) {
                println("replyLooper loop")
                val data = receiveChannel.readUTF8Line()
                println("org.shiroumi.trading.socket.SocketManager, received data: $data")
                // handle received protocol
                val resultProtocol = withContext(socketDispatcher) { handleProtocol(data!!) }
                if (resultProtocol == null) {
                    println("org.shiroumi.trading.socket.SocketManager continue")
                    continue
                }
                // send protocol result
                sendChannel.writeStringUtf8("${serializeProtocol(resultProtocol)}\n")
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            sendChannel.writeStringUtf8("error! \n")
        }
    }

//    // send protocol positive
//    private suspend fun sendLooper(
//        sendChannel: ByteWriteChannel
//    ) {
//        val sendFlow = threadLocalSendingChannel.getOrSet { MutableStateFlow(null) }
//        sendFlow.collect { protocol ->
//            println("sendFlow collected $protocol")
//            protocol ?: return@collect
//            withContext(Dispatchers.IO) {
//                sendChannel.writeStringUtf8("${serializeProtocol(protocol)}\n")
//            }
//        }
//    }
}