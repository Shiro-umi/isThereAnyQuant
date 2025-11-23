package org.shiroumi.trading.schedule.task

import kotlinx.coroutines.runBlocking
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.shiroumi.database.datasource.updateIndustryClassify

class UpdateIndustryClassifyJob : Job {
    override fun execute(context: JobExecutionContext?) {
        runBlocking {
            updateIndustryClassify()
        }
    }
}
