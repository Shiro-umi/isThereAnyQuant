package org.shiroumi.trading

import org.shiroumi.trading.schedule.JobManager

object Task {
    fun init() {
        JobManager.init()
    }

    fun getActiveJobs(): List<JobManager.JobInfo> {
        return JobManager.getActiveJobs()
    }

    fun shutdown() {
        JobManager.shutdown()
    }

    fun standby() {
        JobManager.standby()
    }
}