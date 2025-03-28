package org.shiroumi.trading.schedular

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import org.shiroumi.supervisorScope
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Single-Step Iterator for any task
 */
abstract class SingleStepIterator {

    private val taskFlow: MutableStateFlow<(suspend () -> Unit)?> = MutableStateFlow(null)

    private var continuation: Continuation<Unit>? = null

    private var job: Job? = null

    /**
     * refresh all tasks
     */
    fun registerTasks(tasks: Flow<suspend () -> Unit>) = runBlocking {
        job?.cancelAndJoin()
        job = supervisorScope.launch {
            nextStep()
            taskFlow.collect { task ->
                suspendCancellableCoroutine { cont ->
                    continuation = cont
                    supervisorScope.launch(Dispatchers.IO) { task?.invoke() }
                }
            }
        }
        taskFlow.emitAll(tasks)
    }

    fun nextStep() {
        continuation?.resume(Unit)
        continuation = null
    }
}