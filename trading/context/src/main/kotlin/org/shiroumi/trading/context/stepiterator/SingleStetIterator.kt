package org.shiroumi.trading.context.stepiterator

import Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import supervisorScope
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Single-Step Iterator for any task
 */
open class SingleStepIterator : Logger {

    override val className: String = "SingleStepIterator"
    private var continuation: Continuation<Unit>? = null

    private val taskFlow = MutableSharedFlow<suspend () -> Unit>(replay = Int.MAX_VALUE)

    /**
     * refresh all tasks
     */
    open suspend fun submitTasks(
        tasks: List<suspend () -> Unit>
    ) = suspendCoroutine<Boolean> { cont ->
        supervisorScope.launch {
            tasks.forEach { task -> taskFlow.emit(task) }
            taskFlow.emit {
                cont.resume(nextStep())
            }
            taskFlow.collect { task ->
                task()
                continuation?.let { nextStep() }
            }
        }
    }

    /**
     * do next step
     *
     * @return true if this iterator has next step pending
     */
    open suspend fun nextStep(): Boolean {
        val res = continuation == null
        continuation?.resume(Unit)
        continuation = null
        return res
    }
}