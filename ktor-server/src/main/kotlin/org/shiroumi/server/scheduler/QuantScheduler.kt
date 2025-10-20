@file:OptIn(ExperimentalUuidApi::class, DelicateCoroutinesApi::class)

package org.shiroumi.server.scheduler

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import model.LLMTask
import model.Progress
import model.Quant
import model.TaskList
import utils.logger
import kotlin.uuid.ExperimentalUuidApi

private const val capacity = 3

object QuantScheduler {

    private val logger by logger("QuantScheduler")

    private val mutex: Mutex = Mutex()

    private val supervisorScope: CoroutineScope
        get() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tokenBucket: Channel<Unit> = Channel(capacity = capacity)

    private val pendingQueue: PendingQueue by lazy { PendingQueue(supervisorScope, tokenBucket) }

    private val runningQueue: RunningQueue by lazy { RunningQueue(supervisorScope, tokenBucket) }

    private val errorQueue: ErrorQueue by lazy { ErrorQueue(supervisorScope) }

    val quantListFlow: MutableSharedFlow<TaskList> = MutableSharedFlow()

    var quantList: TaskList = TaskList()

    init {
        repeat(3) {
            tokenBucket.trySend(Unit)
        }
        supervisorScope.launch {
            pendingQueue.startQueue()
            pendingQueue.subscribeSnapshot { pendingList ->
                logger.notify("Pending queue snapshot collect: $pendingList")
                val sorted = pendingList.sortedByDescending { it.triggerTime }
                mutex.withLock {
                    quantList = quantList.copy(pendingList = sorted)
                }
            }
        }
        supervisorScope.launch {
            runningQueue.startQueue()
            runningQueue.subscribeSnapshot { runningList ->
                logger.notify("Running queue snapshot collect: $runningList")
                val sorted = runningList.sortedByDescending { it.triggerTime }
                mutex.withLock {
                    quantList = quantList.copy(runningList = sorted)
                }
            }
        }
        supervisorScope.launch {
            errorQueue.startQueue()
            errorQueue.subscribeSnapshot { errorList ->
                logger.notify("Error queue snapshot collect: $errorList")
                val sorted = errorList.sortedByDescending { it.triggerTime }
                mutex.withLock {
                    quantList = quantList.copy(errorList = sorted)
                }
            }
        }
        supervisorScope.launch {
            pendingQueue.outgoingFlow.collect { quantToRun ->
                runningQueue.submit(quantToRun)
            }
        }
        supervisorScope.launch {
            runningQueue.outgoingFlow.collect { quantError ->
                errorQueue.submit(quantError)
            }
        }
        supervisorScope.launch {
            errorQueue.outgoingFlow.collect { quantRetry ->
                pendingQueue.submit(quantRetry)
            }
        }
        supervisorScope.launch {
            while (true) {
                quantListFlow.emit(quantList)
                delay(1000)
            }
        }
    }

    suspend fun submit(tsCode: String, tasks: List<LLMTask>): Quant {
        val quant = Quant(
            code = tsCode,
//            name = getJoinedCandles(tsCode, 60).name,
            name = tsCode,
            progress = Progress(totalStep = tasks.size),
            tasks = tasks
        )
        pendingQueue.submit(quant)
        return quant
    }
}
