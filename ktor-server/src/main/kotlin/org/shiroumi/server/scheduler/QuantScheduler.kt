@file:OptIn(ExperimentalUuidApi::class, DelicateCoroutinesApi::class)

package org.shiroumi.server.scheduler

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import model.*
//import org.shiroumi.database.old.functioncalling.getStockName
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

    private val doneQueue: DoneQueue by lazy { DoneQueue(supervisorScope) }

    val quantListFlow: MutableSharedFlow<TaskList> = MutableSharedFlow()

    var quantList: TaskList = TaskList()

    init {
        repeat(3) {
            tokenBucket.trySend(Unit)
        }
        supervisorScope.launch {
            pendingQueue.startQueue()
            pendingQueue.subscribeSnapshot { pendingList ->
                mutex.withLock {
                    quantList = quantList.copy(pendingList = pendingList)
                }
            }
        }
        supervisorScope.launch {
            runningQueue.startQueue()
            runningQueue.subscribeSnapshot { runningList ->
                mutex.withLock {
                    quantList = quantList.copy(runningList = runningList)
                }
            }
        }
        supervisorScope.launch {
            errorQueue.startQueue()
            errorQueue.subscribeSnapshot { errorList ->
                mutex.withLock {
                    quantList = quantList.copy(errorList = errorList)
                }
            }
        }
        supervisorScope.launch {
            doneQueue.start()
            doneQueue.subscribeSnapshot { doneList ->
                mutex.withLock {
                    quantList = quantList.copy(doneList = doneList)
                }
            }
        }
        supervisorScope.launch {
            pendingQueue.outgoingFlow.collect { quantToRun ->
                runningQueue.submit(quantToRun)
            }
        }
        supervisorScope.launch {
            runningQueue.outgoingFlow.collect { quantRun ->
                when (quantRun.status) {
                    Status.Done -> doneQueue.submit(quantRun)
                    Status.Error -> errorQueue.submit(quantRun)
                    Status.Pending,
                    Status.Running -> Unit
                }
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

    fun submit(tsCode: String, tasks: List<LLMTask>): Quant {
//        val quant = Quant(
//            code = tsCode,
//            name = getStockName(tsCode = tsCode),
//            progress = Progress(totalStep = tasks.size),
//            tasks = tasks
//        )
//        supervisorScope.launch { pendingQueue.submit(quant) }
        val quant = Quant(
            code = "",
            name = ""
        )
        return quant
    }
}
