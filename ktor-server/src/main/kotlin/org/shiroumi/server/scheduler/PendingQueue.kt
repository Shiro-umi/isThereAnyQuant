package org.shiroumi.server.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import model.Quant
import org.shiroumi.server.scheduler.base.FlowQueue

class PendingQueue(
    scope: CoroutineScope,
    val tokenBucket: Channel<Unit>
) : FlowQueue(scope) {

    private val _outgoingFlow: MutableSharedFlow<Quant> = MutableSharedFlow()
    val outgoingFlow: SharedFlow<Quant> get() = _outgoingFlow

    override suspend fun onSubmit(quant: Quant) {
        quant.update()
    }

    override suspend fun onExecute(quant: Quant) {
        tokenBucket.receive()
        _outgoingFlow.emit(quant)
        quant.drop()
    }

    fun subscribeSnapshot(collector: FlowCollector<List<Quant>>) {
        scope.launch { stateFlow.collect(collector) }
    }
}