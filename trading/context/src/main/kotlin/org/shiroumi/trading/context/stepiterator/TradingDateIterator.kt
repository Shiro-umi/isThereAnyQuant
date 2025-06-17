package org.shiroumi.trading.context.stepiterator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.ktorm.dsl.asc
import org.ktorm.dsl.limit
import org.ktorm.dsl.map
import org.ktorm.dsl.orderBy
import org.ktorm.dsl.select
import org.shiroumi.database.table.TradingDateTable
import org.shiroumi.trading.context.Context
import supervisorScope
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class TradingDateIterator(
    val context: Context
) : SingleStepIterator() {

    private var inner: SingleStepIterator? = null

    private val tradingDate: List<String?>
        get() = TradingDateTable.query { query ->
            query.select()
                .orderBy(TradingDateTable.date.asc())
                .limit(3)
                .map { column -> column[TradingDateTable.date] }
                .toList()
        }

    override suspend fun submitTasks(
        tasks: List<suspend () -> Unit>
    ): Boolean = suspendCoroutine { cont ->
        supervisorScope.launch(Dispatchers.IO) {
            val res = super.submitTasks(
                tradingDate.map { date ->
                    suspend task@{
                        date ?: return@task
                        context.tradingDate = date
                        warning("current date: ${context.tradingDate}")
                        val res = SingleStepIterator().also { inner = it }.submitTasks(tasks)
                        if (res) nextStep()
                    }
                }
            )
            if (res) cont.resume(true)
        }
    }

    override suspend fun nextStep(): Boolean {
        val inner = inner ?: return super.nextStep()
        return if (inner.nextStep()) super.nextStep() else false
    }
}