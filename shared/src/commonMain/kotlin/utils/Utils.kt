package utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDate


expect val cpuCores: Int

val String?.f: Float
    get() = if (this.isNullOrBlank()) 0f else this.toFloat()

val supervisorScope: CoroutineScope
    get() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

val String.localDate: LocalDate
    get() {
        val parsedDate = "${substring(0, 4)}-${substring(4, 6)}-${substring(6, 8)}"
        return LocalDate.parse(parsedDate)
    }

//val String.asSingleDispatcher: ExecutorCoroutineDispatcher
//    get() = Executors.newSingleThreadExecutor { r ->
//        Thread(r, "thread_dispatcher_$this").apply { isDaemon = true }
//    }.asCoroutineDispatcher()
//
//val String.asDispatcher: ExecutorCoroutineDispatcher
//    get() = Executors.newCachedThreadPool { r ->
//        Thread(r, "thread_dispatcher_$this").apply { isDaemon = true }
//    }.asCoroutineDispatcher()

