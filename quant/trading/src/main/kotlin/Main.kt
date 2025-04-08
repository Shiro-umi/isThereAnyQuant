import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import org.shiroumi.supervisorScope
import org.shiroumi.trading.schedule.Schedular
import org.shiroumi.trading.schedule.SchedularType
import org.shiroumi.trading.schedule.SingleStepTask
import org.shiroumi.trading.schedule.threadLocalSchedular
import org.shiroumi.trading.socket.ProtocolSocketManager
import java.lang.Thread.sleep
import java.util.concurrent.Executors
import kotlin.concurrent.getOrSet

fun main(args: Array<String>) {
    ProtocolSocketManager().bindToPort(6332)
}

private val socketDispatcher = Executors.newSingleThreadExecutor { r ->
    Thread(r, "socket_looper").apply { isDaemon = true }
}.asCoroutineDispatcher()

//fun main(args: Array<String>) {
//    runBlocking {
//        init()
//    }
//}

//suspend fun init() {
//    val schedular = threadLocalSchedular.getOrSet { Schedular(SchedularType.Backtesting) }
//    val actions = (1..5).map { i ->
//        SingleStepTask(socketDispatcher) { println("task$i") }
//    }.asFlow()
//    println(actions)
//    schedular.registerTasks(actions)
//
//    val job = supervisorScope.launch(Dispatchers.IO) {
//        while (true) {
//            sleep(1000)
//            schedular.nextStep()
//        }
//    }
//    while (job.isActive) {
//        sleep(1000)
//    }
//}
