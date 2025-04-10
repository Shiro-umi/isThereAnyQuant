import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

val Dispatchers.socket_main: ExecutorCoroutineDispatcher by lazy {
    Executors.newSingleThreadExecutor { r ->
        Thread(r, "socket_main").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

val threadLocalJob: ThreadLocal<Job> = ThreadLocal<Job>()