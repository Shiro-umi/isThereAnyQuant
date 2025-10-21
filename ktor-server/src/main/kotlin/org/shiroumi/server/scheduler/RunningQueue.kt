@file:OptIn(ExperimentalUuidApi::class)

package org.shiroumi.server.scheduler

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import model.Quant
import model.Status
import org.shiroumi.server.scheduler.base.FlowQueue
import utils.logger
import kotlin.uuid.ExperimentalUuidApi

class RunningQueue(
    scope: CoroutineScope,
    val tokenBucket: Channel<Unit>
) : FlowQueue(scope) {

    val logger by logger("RunningQueue")

    private val _outgoingFlow: MutableSharedFlow<Quant> = MutableSharedFlow()
    val outgoingFlow: SharedFlow<Quant> get() = _outgoingFlow

    override suspend fun onSubmit(quant: Quant) {
        quant.copy(status = Status.Running).update()
    }

    override suspend fun onExecute(quant: Quant) {
        logger.accept("onExecute $quant")
        var qt = quant.copy(status = Status.Running)
        qt.update()
        try {
            quant.tasks?.forEachIndexed { i, task ->
                if (qt.status == Status.Error) return@forEachIndexed
                val llmJob = scope.launch(context = CoroutineExceptionHandler { _, t ->
                    t.printStackTrace()
                    scope.launch {
                        qt = qt.copy(status = Status.Error)
                        qt.update()
                    }
                }) {
                    runCatching { task(quant) }.onFailure {
                        it.printStackTrace()
                        qt = qt.copy(status = Status.Error)
                    }
                }
                val taskStartTime = now
                while (llmJob.isActive) {
                    qt = qt.copy(
                        progress = qt.progress.copy(
                            step = i + 1,
                            totalStep = qt.tasks?.size ?: 1,
                            description = "running task ${i + 1}/${qt.progress.totalStep}",
                            progress = (now - taskStartTime) / 10000f
                        )
                    )
                    qt.update()
                    delay(500L)
                }
            }
            val qt = qt.copy(status = if (qt.status == Status.Running) Status.Done else qt.status)
            qt.update()
        } finally {
            qt.drop()
            _outgoingFlow.emit(qt)
            tokenBucket.send(Unit)
        }
    }

    suspend fun subscribeSnapshot(collector: FlowCollector<List<Quant>>) {
        stateFlow.collect(collector)
    }
}