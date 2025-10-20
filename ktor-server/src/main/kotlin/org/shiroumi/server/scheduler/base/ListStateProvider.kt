package org.shiroumi.server.scheduler.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import model.Quant
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
open class ListStateProvider(val scope: CoroutineScope) {

    private val mutex: Mutex = Mutex()

    private val set: MutableSet<Quant> = mutableSetOf()

    private val _stateFLow: MutableSharedFlow<List<Quant>> = MutableSharedFlow()

    protected val stateFlow: Flow<List<Quant>>
        get() = _stateFLow

    suspend fun Quant.update() = mutex.withLock {
        set.remove(this)
        set.add(copy(tasks = null))
    }

    suspend fun Quant.drop() = mutex.withLock { set.remove(this) }

    protected fun start() = scope.launch {
        while (isActive) {
            val stateList = set.sortedBy { it.triggerTime }
            _stateFLow.emit(stateList)
            delay(1000L)
        }
    }
}