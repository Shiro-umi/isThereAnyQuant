package org.shiroumi.trading.schedule

import kotlinx.serialization.json.Json
import org.quartz.*
import org.quartz.impl.StdSchedulerFactory
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object JobManager {
    private val scheduler: Scheduler = StdSchedulerFactory.getDefaultScheduler()
    private val jobConfigs = ConcurrentHashMap<String, JobConfig>()
    private const val CONFIG_FILE = "schedule_task.json"

    fun init() {
        startFileWatcher()
        reloadConfig()
        scheduler.start()
    }

    private fun startFileWatcher() {
        val file = File(CONFIG_FILE)
        file.parentFile?.mkdirs()
        Thread({
            var lastModified = 0L
            Thread.sleep(5000)
            while (true) {
                if (file.exists() && file.lastModified() != lastModified) {
                    lastModified = file.lastModified()
                    reloadConfig()
                }

            }
        }, "ConfigWatcher").start()
    }

    private fun reloadConfig() {
        try {
            jobConfigs.clear()
            scheduler.clear()
            val jsonStr = File(CONFIG_FILE).takeIf { it.exists() }?.readText() ?: return
            val configs = Json.decodeFromString<List<JobConfig>>(jsonStr)
            configs.forEach { config ->
                updateJob(config)
            }

            val currentKeys = configs.map { it.jobName }.toSet()
            jobConfigs.keys.filterNot { currentKeys.contains(it) }.forEach {
                unScheduleJob(it)
            }
        } catch (e: Exception) {
            println("Failed to load config: ${e.message}")
        }
    }


    private fun updateJob(config: JobConfig) {
        val oldConfig = jobConfigs[config.jobName]

        when {
            oldConfig == null && config.enable -> scheduleJob(config)
            oldConfig != null && !config.enable -> unScheduleJob(config.jobName)
            oldConfig != null && oldConfig != config -> {
                unScheduleJob(config.jobName)
                scheduleJob(config)
            }
        }
    }

    private fun scheduleJob(config: JobConfig) {
        val job = JobBuilder.newJob(Class.forName(config.jobClass) as Class<Job>)
            .withIdentity(config.jobName)
            .usingJobData(JobDataMap(config.parameters))
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity("${config.jobName}_trigger")
            .withSchedule(CronScheduleBuilder.cronSchedule(config.cronExpression))
            .build()
        scheduleJobIfNotExists(job, trigger)
        jobConfigs[config.jobName] = config
    }

    private fun scheduleJobIfNotExists(jobDetail: JobDetail, trigger: Trigger) {
        val jobKey = jobDetail.key
        if (!scheduler.checkExists(jobKey)) {
            scheduler.scheduleJob(jobDetail, trigger)
        } else {
            scheduler.deleteJob(jobKey)
            scheduler.scheduleJob(jobDetail, trigger)
        }
    }

    private fun unScheduleJob(jobName: String) {
        try {
            val jobKey = JobKey.jobKey(jobName)
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey)
                jobConfigs.remove(jobName)
                println("Job $jobName unscheduled successfully")
            }
        } catch (e: SchedulerException) {
            println("Failed to unschedule job $jobName: ${e.message}")
        }
    }

    fun getActiveJobs(): List<JobInfo> {
        return jobConfigs.values.map { config ->
            val jobKey = JobKey.jobKey(config.jobName)
            val triggers = scheduler.getTriggersOfJob(jobKey)

            JobInfo(
                name = config.jobName,
                cron = config.cronExpression,
                status = if (triggers.isNotEmpty()) JobStatus.RUNNING else JobStatus.STOPPED,
                nextFireTime = triggers.firstOrNull()?.nextFireTime,
                parameters = config.parameters
            )
        }
    }

    fun shutdown() = scheduler.shutdown()

    fun standby() = scheduler.standby()

    data class JobInfo(
        val name: String,
        val cron: String,
        val status: JobStatus,
        val nextFireTime: Date?,
        val parameters: Map<String, String>?
    )
}

enum class JobStatus {
    RUNNING,
    PAUSED,
    STOPPED,
    ERROR
}

