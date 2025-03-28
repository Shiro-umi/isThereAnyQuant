package org.shiroumi.trading.schedular

class Schedular(val type: SchedularType) : SingleStepIterator() {

    fun registerSchedules() {

    }
}

sealed class SchedularType {
    data object Trading : SchedularType()
    data object Backtesting : SchedularType()
}

/**
 * ThreadLocal for each Schedular thread
 */
val threadLocalSchedular: ThreadLocal<Schedular> = ThreadLocal()