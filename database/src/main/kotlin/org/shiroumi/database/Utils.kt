@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package org.shiroumi.database

import Logger
import cpuCores
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.concurrent.atomics.ExperimentalAtomicApi

data class AsyncTask<T, U>(
    val tag: String,
    val task: suspend (T) -> U,
)

suspend fun <T, U> List<T>.scheduledAsync(
    logger: Logger,
    asyncTasks: List<AsyncTask<T, U>>,
    done: suspend (origin: T, res: List<U>) -> Unit
) = withContext(Dispatchers.IO) {
    val source = this@scheduledAsync
    var shutdown = false
    val tokenBuckets = asyncTasks.map {
        val tokenBucket = Channel<Unit>(500)
        launch {
            while (!shutdown) {
                tokenBucket.send(Unit)
                delay(60_000L / 500L)
            }
        }
        tokenBucket
    }

    var finishedCount = 0
    source.asFlow().flatMapMerge(concurrency = cpuCores * 3) { item ->
        flow {
            flow {
                asyncTasks.mapIndexed { i, (_, task) ->
                    tokenBuckets[i].receive()
                    task(item)
                }.also {
                    emit(it)
                }
            }.retry(retries = 3) retry@{ cause ->
                logger.error("${cause.message}, retrying..")
                delay(1000)
                return@retry true
            }.flowOn(Dispatchers.IO).collect { res ->
                emit(item to res)
            }
        }
    }.collect { (item, data) ->
        done(item, data)
        finishedCount++
        logger.notify("$finishedCount/${source.size} done.")
    }
    shutdown = true
}
