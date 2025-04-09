import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.shiroumi.Trader
import org.shiroumi.trading.schedule.Schedular
import org.shiroumi.trading.schedule.SchedularType
import org.shiroumi.trading.schedule.threadLocalSchedular
import strategy.AbsStrategy
import strategy.PyStrategy
import java.lang.Thread.sleep

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
//        (6000 until 6500).random()
        6332
    }

    val trader: Trader by lazy { Trader() }

    val strategy: AbsStrategy by lazy {
        PyStrategy(fileName = scriptName, port = socketPort)
    }

    fun initialize(type: Type) = runBlocking {
        println("Trading initializing as type: ${type.name}")
        initSocket()
    }

    private fun initSocket() = runBlocking(
        context = CoroutineName("socket super thread")
    ) superThread@{
        val selectorManager = SelectorManager(Dispatchers.IO + CoroutineName("socket thread: $socketPort"))
        val serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", socketPort)
        println("Server is listening at ${serverSocket.localAddress}, from thread: ${Thread.currentThread().name}")
        threadLocalSchedular.set(Schedular(SchedularType.Backtesting))
        while (true) {
            val socket = serverSocket.accept()
            launch {
                val receiveChannel = socket.openReadChannel()
                val sendChannel = socket.openWriteChannel(autoFlush = true)
                sendChannel.writeStringUtf8("Server started listening\n")
                launch {
                    while (true) {
                        sendChannel.writeStringUtf8("Server is living, ${System.currentTimeMillis()}\n")
                        println("msg sent")
                        sleep(1000)
                    }
                }
                try {
                    while (true) {
                        val input = receiveChannel.readUTF8Line()?.also {
                            println("received protocol $it from ${socket.remoteAddress}")
                        } ?: continue
                        println("received protocol on thread ${Thread.currentThread().name}")
                        withContext(this@superThread.coroutineContext) {
                            println("handle protocol on thread ${Thread.currentThread().name}")
//                            // handle received protocol
//                            val protocol = handleProtocol(ProtocolDecoder.decodeFromString<Protocol>(input))
//                            // send protocol result
//                            sendChannel.writeStringUtf8("${ProtocolDecoder.encodeToString(protocol)}\n")
                        }
                    }
                } catch (e: Throwable) {
                    socket.close()
                }
            }
        }
    }
}