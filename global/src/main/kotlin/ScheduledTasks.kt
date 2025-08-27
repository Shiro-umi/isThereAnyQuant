import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledTasks<T>(
    val concurrency: Int = cpuCores * 2,
    val frequency: Int = 200
) {

    val tokenBucket by lazy { TokenBucket(frequency = frequency) }

    private val tasks = mutableListOf<Pair<String, List<suspend () -> T>>>()

    fun emit(tag: String, vararg task: suspend () -> T) {
        tasks.add(tag to task.toList())
    }

    fun schedule() = tasks.asFlow().flatMapMerge(concurrency) { (tag, tasks) ->
        channelFlow channel@{
            tokenBucket.receive()
            println("request for $tag")
            send(tag to tasks.map { task -> async { task() } }.awaitAll())
        }.retry(retries = 3).flowOn(Dispatchers.IO)
    }
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