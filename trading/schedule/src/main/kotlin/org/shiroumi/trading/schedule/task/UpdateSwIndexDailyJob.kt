package org.shiroumi.trading.schedule.task

import kotlinx.coroutines.runBlocking
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.shiroumi.database.old.datasource.updateSwIndexDaily

class UpdateSwIndexDailyJob : Job {
    override fun execute(context: JobExecutionContext?) {
        runBlocking {
            updateSwIndexDaily()
        }
    }
}
