@file:OptIn(ExperimentalUuidApi::class)

package org.shiroumi.server.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import model.Progress
import model.Quant
import model.Status
import org.shiroumi.server.scheduler.base.ListStateProvider
import kotlin.uuid.ExperimentalUuidApi

class ErrorQueue(
    scope: CoroutineScope,
) : ListStateProvider(scope) {

    private val _outgoingFlow: MutableSharedFlow<Quant> = MutableSharedFlow()
    val outgoingFlow: SharedFlow<Quant> get() = _outgoingFlow

    suspend fun submit(quant: Quant) {
        quant.update()
    }

    suspend fun retry(quant: Quant) {
        val newQuant = quant.copy(
            status = Status.Pending,
            triggerTime = Clock.System.now().toEpochMilliseconds(),
            progress = Progress()
        )
        _outgoingFlow.emit(newQuant)
        quant.drop()
    }

    suspend fun subscribeSnapshot(collector: FlowCollector<List<Quant>>) {
        stateFlow.collect(collector)
    }

    fun startQueue() {
        scope.launch { start() }
    }
}