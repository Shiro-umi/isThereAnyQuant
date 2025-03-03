package org.shiroumi.trading

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.shiroumi.supervisorScope

// todo
class Schedular(
    val taskList: List<() -> Unit>
) {
    private val taskFLow = MutableSharedFlow<suspend () -> Unit>()

    init {
        supervisorScope.launch {
            taskFLow.collect { t -> t() }
        }
    }
}

data class ScheduledTask(
    val action: () -> Unit,
    
)