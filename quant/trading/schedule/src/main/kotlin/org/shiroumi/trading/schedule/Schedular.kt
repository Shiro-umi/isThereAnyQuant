package org.shiroumi.trading.schedule

class Schedular(val type: SchedularType) : SingleStepIterator() {

    fun registerSchedules() {

    }
}

sealed class SchedularType {
    data object Trading : SchedularType() {
//        fun get() =
    }
    data object Backtesting : SchedularType()

}

/**
 * ThreadLocal for each Schedular thread
 */
val threadLocalSchedular: ThreadLocal<Schedular> = ThreadLocal()