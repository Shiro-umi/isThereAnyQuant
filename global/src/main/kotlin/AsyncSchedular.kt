import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
suspend fun <T> Flow<AsyncTask<T>>.runConcurrent(
    tag: String,
    count: AtomicInt = AtomicInt(0),
    concurrency: Int = cpuCores * 2,
    frequency: Int = 1000 ,
): Flow<List<T>> {
    val logger by logger(tag)
    val tokenBucket = TokenBucket(frequency = frequency)
    val size = this@runConcurrent.toList().size
    return flatMapMerge(concurrency = concurrency) { task ->
        channelFlow {
            runBlocking {
                tokenBucket.receive()
                val res = task.a.map { action -> async { action() } }.awaitAll()
                logger.info("task ${task.tag} completed. ${count.addAndFetch(1)}/$size")
                send(res)
            }

        }.retry(retries = 3).flowOn(Dispatchers.IO)
    }
}


//@OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
//fun <O> Flow<AsyncTask<O>>.runConcurrent(
//    count: AtomicInt = AtomicInt(0),
//    concurrency: Int = cpuCores * 2,
//) = flatMapMerge(concurrency) { task ->
//    task.onFlow?.invoke()
//    task.action().retry(3).flowOn(Dispatchers.IO).map { res ->
//        count.addAndFetch(1) to res
//    }
//}


class AsyncTask<T>(
    val tag: String,
    vararg actions: suspend () -> T
) {

    val a: List<suspend () -> T> = actions.toList()
}

class TokenBucket(frequency: Int) {

    private val channel: Channel<Unit> = Channel(frequency)

    suspend fun receive() = channel.receive()

    init {
        supervisorScope.launch {
            while (true) {
                channel.send(Unit)
                delay(60_000L / frequency)
            }
        }
    }
}