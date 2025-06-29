import kotlinx.coroutines.*
import java.util.concurrent.Executors

val cpuCores = Runtime.getRuntime().availableProcessors()

fun printProgressBar(total: Int, current: Int) = if (total == 0) {
    println("\r[....................] 0/0 (0%)")
} else {
    // 计算完成百分比
    val percent = current.toDouble() / total
    val completedChars = (percent * 20).toInt() // 假设总长度为20

    print("\r[")
    (0 until completedChars).forEach { _ ->
        print("=")
    }
    (completedChars..19).forEach { _ ->
        print(".")
    }
    print("] " + current + "/" + total + " (" + (percent * 100).toInt() + "%)")
}

val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

val String.asDispatcher: ExecutorCoroutineDispatcher
    get() = Executors.newSingleThreadExecutor { r ->
        Thread(r, "thread_dispatcher_$this").apply { isDaemon = true }
    }.asCoroutineDispatcher()
