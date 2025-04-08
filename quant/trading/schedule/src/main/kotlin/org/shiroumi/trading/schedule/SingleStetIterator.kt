package org.shiroumi.trading.schedule

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import org.shiroumi.supervisorScope
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Single-Step Iterator for any task
 */
abstract class SingleStepIterator {

    val tag: String = this::class.simpleName!!

    private var continuation: Continuation<Unit>? = null

    /**
     * refresh all tasks
     */
    fun <T> registerTasks(tasks: Flow<SingleStepTask<T>>) = supervisorScope.launch {
        println("$tag: registerTasks")
        val workerContext = coroutineContext
        runBlocking {
            tasks.collect { task ->
                suspendCoroutine { cont ->
                    continuation = cont
                    println("$tag: task $task ready to execute")
                    runBlocking { task.executeWithContext() }
                }
            }
        }
    }

    suspend fun nextStep() {
        println("SingleStepIterator, nextStep()")
        if (continuation == null) throw CancellationException()
        continuation?.resume(Unit)
        continuation = null
    }
}

data class SingleStepTask<T>(
//    private val coroutineContext: CoroutineContext,
    private val sendingChannel: Channel<T>,
    private val action: suspend (Channel<T>) -> Unit
) {
    suspend fun executeWithContext() {
        println("SingleStepIterator, task invoked")
        action(sendingChannel)
//        withContext(coroutineContext) {
//            println("SingleStepIterator, task invoked")
//        }
    }
}