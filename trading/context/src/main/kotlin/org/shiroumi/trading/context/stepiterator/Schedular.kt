package org.shiroumi.trading.context.stepiterator

import kotlinx.coroutines.flow.Flow
import org.ktorm.dsl.desc
import org.ktorm.dsl.limit
import org.ktorm.dsl.map
import org.ktorm.dsl.orderBy
import org.ktorm.dsl.select
import org.shiroumi.database.table.TradingDateTable

class TradingDateIterator() : SingleStepIterator() {

    private var innerIterator: SingleStepIterator? = null

    override suspend fun submitTasks(tasks: Flow<suspend () -> Unit>) {
        TradingDateTable.query { query ->
            query.select()
                .orderBy(TradingDateTable.date.desc())
                .limit(1)
                .map { column -> column[TradingDateTable.date] }
                .toList()
        }.map { date ->
            suspend {
                object : SingleStepIterator() {}.also { innerIterator = it }.submitTasks(tasks)
            }
        }
    }

    override suspend fun nextStep(): Boolean {
        val inner = innerIterator ?: return super.nextStep()
        if (!inner.nextStep()) super.nextStep()
        return true // no use
    }
}