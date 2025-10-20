package org.shiroumi.server.scheduler.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import model.Quant


abstract class FlowQueue(scope: CoroutineScope) : ListStateProvider(scope) {

    val now: Long
        get() = Clock.System.now().toEpochMilliseconds()

    private val sharedFlow: MutableSharedFlow<Quant> = MutableSharedFlow()

    open suspend fun submit(quant: Quant) {
        onSubmit(quant = quant)
        sharedFlow.emit(quant)
    }

    abstract suspend fun onSubmit(quant: Quant)

    abstract suspend fun onExecute(quant: Quant)

    fun startQueue() {
        scope.launch { super.start() }
        scope.launch {
            sharedFlow.collect { onExecute(it) }
        }
    }
}