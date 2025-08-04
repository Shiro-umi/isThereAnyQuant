//@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
//
//package org.shiroumi.database
//
//import Logger
//import cpuCores
//import kotlinx.coroutines.*
//import kotlinx.coroutines.channels.Channel
//import kotlinx.coroutines.flow.*
//import kotlin.concurrent.atomics.ExperimentalAtomicApi
//
//data class SeqTask<T, U>(
//    val tag: String,
//    val task: suspend (Int, T) -> U,
//)
//
//
//
//suspend fun <T, U> List<T>.scheduledAsync(
//    concurrency: Int = cpuCores * 2,
//    logger: Logger? = null,
//    tokenBucketProvider: suspend () -> TokenBucket = { TokenBucket() },
//    asyncTasks: List<SeqTask<T, U>>,
//    done: suspend (origin: T, res: List<U>) -> Unit
//) = withContext(Dispatchers.IO) {
//    val source = this@scheduledAsync
//    var shutdown = false
//
//
//    val tokenBuckets = asyncTasks.map {
//        tokenBucketProvider().also { bucket -> with(bucket) { start() } }
//    }
//
//    var finishedCount = 0
//    source.asFlow().flatMapMerge(concurrency = concurrency) { item ->
//        flow {
//            val start = System.currentTimeMillis()
//            logger?.warning("item: ${item}, start: $start")
//            val res = asyncTasks.mapIndexed { i, (_, task) ->
//                tokenBuckets[i].receive()
//                task(i, item)
//            }
//            emit(item to res)
//            val end = System.currentTimeMillis()
//            logger?.warning("item: ${item}, end: $end, cost: ${end - start} ms")
//        }.retry(retries = 3) retry@{ cause ->
//            logger?.error("${cause.message}, retrying..")
//            delay(1000)
//            return@retry true
//        }.flowOn(Dispatchers.IO)
//    }
//
//        .collect { (item, data) ->
//        done(item, data)
//        finishedCount++
//        logger?.notify("$finishedCount/${source.size} done.")
//    }
//    shutdown = true
//}
