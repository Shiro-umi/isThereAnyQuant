package org.shiroumi.trading.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

/**
 * Single-step task
 * each task defins an action in one day
 *
 * @param sendingChannel when action is done, use this to send
 */
data class SingleStepTask<T>(
    private val sendingChannel: Channel<T>,
    private val action: suspend (Channel<T>) -> Unit
) {
    suspend fun executeWithContext() {
        println("SingleStepIterator, task invoked")
        action(sendingChannel)
    }
}