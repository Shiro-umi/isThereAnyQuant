@file:OptIn(ExperimentalUuidApi::class)

package org.shiroumi.server.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import model.Quant
import model.Status
import org.shiroumi.database.functioncalling.fetchDoneTasks
import org.shiroumi.server.scheduler.base.FlowQueue
import org.shiroumi.server.scheduler.base.ListStateProvider
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class DoneQueue(scope: CoroutineScope) : ListStateProvider(scope) {

    fun subscribeSnapshot(collector: FlowCollector<List<Quant>>) {
        scope.launch { stateFlow.collect(collector) }
    }

    public override fun start() = runBlocking {
        val savedTasks = fetchDoneTasks().map { task ->
            Quant(
                uuid = Uuid.parse(task[0]),
                code = task[1],
                name = task[2],
                targetDate = task[3],
                triggerTime = task[4].toLong(),
                status = Status.Done,
            )
        }
        updateQuantList(savedTasks)
        super.start()
    }
}