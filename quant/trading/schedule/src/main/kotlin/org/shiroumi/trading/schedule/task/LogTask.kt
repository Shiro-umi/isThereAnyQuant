package org.shiroumi.trading.schedule.task

import org.quartz.Job
import org.quartz.JobExecutionContext
import java.time.LocalTime

class LogTask : Job {
    override fun execute(jobCtx: JobExecutionContext?) {
        val currentDateTime = LocalTime.now();
        val logMsg = jobCtx?.mergedJobDataMap?.get("logMsg")
        println("每秒钟执行一次------ $currentDateTime ------${logMsg}")
    }
}