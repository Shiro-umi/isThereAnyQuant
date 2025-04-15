package org.shiroumi.trading.context.stepiterator

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Single-Step Iterator for any task
 */
open class SingleStepIterator {
    val tag: String = this::class.simpleName!!

    private var continuation: Continuation<Unit>? = null

//    val lastExecutedTask: MutableStateFlow<SingleStepTask<*>?> = MutableStateFlow(null)

    /**
     * refresh all tasks
     */
    open suspend fun submitTasks(tasks: Flow<suspend () -> Unit>) {
        println("$tag: registerTasks")
        runBlocking {
            tasks.collect { task ->
                suspendCoroutine { cont ->
                    continuation = cont
                    runBlocking { task }
                }
            }
        }
    }

    /**
     * do next step
     *
     * @return true if this iterator has next step pending
     */
    open suspend fun nextStep(): Boolean {
        println("SingleStepIterator, nextStep()")
        val res = continuation != null
        continuation?.resume(Unit)
        continuation = null
        return res
    }
}