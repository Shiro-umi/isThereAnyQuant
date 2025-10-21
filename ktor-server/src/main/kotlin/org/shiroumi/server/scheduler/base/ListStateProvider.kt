package org.shiroumi.server.scheduler.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import model.Quant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
open class ListStateProvider(val scope: CoroutineScope) {

    private val mutex: Mutex = Mutex()

    private val map: MutableMap<Uuid, Quant> = hashMapOf()

    private val _stateFLow: MutableSharedFlow<List<Quant>> = MutableSharedFlow()

    protected val stateFlow: Flow<List<Quant>>
        get() = _stateFLow

    suspend fun Quant.update() = mutex.withLock {
        map[uuid] = copy(tasks = null)
    }

    suspend fun updateQuantList(quantList: List<Quant>) = mutex.withLock {
        map.putAll(quantList.associateBy { it.uuid })
    }

    suspend fun Quant.drop() = mutex.withLock { map.remove(uuid) }

    protected open fun start() = scope.launch {
        while (isActive) {
            val stateList = map.values.sortedBy { it.triggerTime }
            _stateFLow.emit(stateList)
            delay(1000L)
        }
    }
}