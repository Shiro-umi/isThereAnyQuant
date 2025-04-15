@file:Suppress("unused")

package org.shiroumi.trading.schedule.task

import org.quartz.Job
import org.quartz.JobExecutionContext
import java.time.LocalTime

class DailyTask : Job {
    override fun execute(jobCtx: JobExecutionContext?) {
        val currentDateTime = LocalTime.now();
        println("每分钟执行一次++++++ $currentDateTime ++++++")
    }
}