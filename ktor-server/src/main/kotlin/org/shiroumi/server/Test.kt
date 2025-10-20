package org.shiroumi.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import model.Quant
import kotlin.uuid.ExperimentalUuidApi

private val json = Json { prettyPrint = true }

private val supervisorScope: CoroutineScope
    get() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(ExperimentalUuidApi::class)
fun main() {
    runBlocking {
        val tokenBucket = Channel<Unit>(3)
        val pq = PendingQueue(supervisorScope, tokenBucket)
        repeat(3) { tokenBucket.send(Unit) }
        pq.startQueue()
        supervisorScope.launch {
            repeat(5) { i ->
                delay(2000)
                launch { pq.submit(Quant(code = "$i", name = "$i")) }
            }
        }
        pq.subscribeSnapshot { list ->
            println("list updated: ${list.size}")
        }
        while (true) {
            delay(1000)
        }
    }
}


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
//        quant.drop()
    }

    fun subscribeSnapshot(collector: FlowCollector<List<Quant>>) {
        scope.launch { stateFlow.collect(collector) }
    }
}


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
            sharedFlow.collect {
                scope.launch { onExecute(it) }
            }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
open class ListStateProvider(val scope: CoroutineScope) {

    private val mutex: Mutex = Mutex()

    private val set: MutableSet<Quant> = mutableSetOf()

    private val _stateFLow: MutableStateFlow<List<Quant>> = MutableStateFlow(listOf())

    protected val stateFlow: Flow<List<Quant>>
        get() = _stateFLow

    suspend fun Quant.update() = mutex.withLock { set.add(copy(tasks = null)) }

    suspend fun Quant.drop() = mutex.withLock { set.remove(this) }

    protected fun start() = scope.launch {
        while (isActive) {
            _stateFLow.emit(set.sortedBy { it.triggerTime })
            delay(1000L)
        }
    }
}