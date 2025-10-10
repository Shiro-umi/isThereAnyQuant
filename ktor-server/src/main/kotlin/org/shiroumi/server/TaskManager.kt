@file:OptIn(ExperimentalUuidApi::class)

package org.shiroumi.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.shiroumi.database.functioncalling.fetchDoneTasks
import org.shiroumi.database.functioncalling.getJoinedCandles
import org.shiroumi.database.functioncalling.upsertStrategy
import org.shiroumi.server.model.Progress
import org.shiroumi.server.model.TaskModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.get
import kotlin.uuid.ExperimentalUuidApi

// 使用 object 实现单例模式
object TaskManager {
    // 可配置的最大并发任务数
    var maxConcurrentTasks: Int = 3

    // 存储所有任务的当前状态，是任务状态的唯一真实来源
    private val _tasks = ConcurrentHashMap<String, Task>()

    val tasks: List<Task>
        get() = _tasks.values.toList()

    // 等待执行的任务队列
    private val pendingTasks = ConcurrentLinkedQueue<String>()

    // 当前正在运行的任务计数器
    private val runningTaskCount = AtomicInteger(0)

    // 用于调度逻辑的锁对象，防止并发调度时出现竞态条件
    private val schedulingLock = Any()

    fun submitTask(code: String, actions: List<suspend () -> String>): Task {
        val task = _tasks.computeIfAbsent(code) {
            println("Submitting a new task for code: $code. It's now pending.")
            pendingTasks.add(code)
            val name = getJoinedCandles(code, 60).name
            Task(code = code, name = name, tradeDate = today, status = TaskStatus.Pending, actions = actions)
        }
        // 每次提交后都尝试调度，以防有空闲槽位
        tryToScheduleNext()
        return task
    }

    fun getTasksList(): List<TaskModel> {
        val res = mutableListOf<TaskModel>()
        val notDoneTasks = _tasks.values.map { it.taskModel }
        val doneTasks = fetchDoneTasks().map { strategy ->
            TaskModel(
                id = strategy[0],
                code = strategy[1],
                tradeDate = strategy[2],
                name = strategy[3],
                status = strategy[4],
                progress = Progress()
            )
        }
        res.addAll(notDoneTasks)
        res.addAll(doneTasks)
        return res
    }

    val doneList: MutableList<TaskModel> by lazy {
        fetchDoneTasks().map { strategy ->
            TaskModel(
                id = strategy[0],
                code = strategy[1],
                tradeDate = strategy[2],
                name = strategy[3],
                status = strategy[4],
                progress = Progress()
            )
        }.toMutableList()
    }

    fun getTaskByCode(code: String): Task? {
        return _tasks[code]
    }

    /**
     * 尝试调度并执行队列中的下一个任务。
     * 此方法是线程安全的。
     */
    private fun tryToScheduleNext() {
        synchronized(schedulingLock) {
            // 当还有空闲槽位并且队列里有待处理的任务时
            while (runningTaskCount.get() < maxConcurrentTasks && pendingTasks.isNotEmpty()) {
                val code = pendingTasks.poll() ?: continue // 从队列中取出一个任务
                // 增加运行任务计数
                runningTaskCount.incrementAndGet()
                val task = _tasks[code]!!
                // 启动任务协程
                launchTaskCoroutine(task)
            }
        }
    }

    private fun launchTaskCoroutine(task: Task) {
        // 更新任务状态为 Running
        task.status = TaskStatus.Running
        println("Task for code '${task.code}' is now running.")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                task.actions.forEachIndexed { i, action ->
                    val llmJob = launch {
                        val res = action()
                        if (i == task.actions.lastIndex) {
                            println("code: ${task.code}, last action done, insert to database")
                            val name = getJoinedCandles(tsCode = task.code, 60).name
                            upsertStrategy(task.code, name, today, res)
                        }
                    }
                    val start = System.currentTimeMillis()
                    while (llmJob.isActive) {
                        task.updateProgress(
                            i,
                            "${task.code}: running task $i",
                            (System.currentTimeMillis() - start) / if (i == 0) 180000f else 120000f
                        )
                        delay(1500)
                    }
                }

                // 任务成功完成
                task.status = TaskStatus.Done
//                doneList.add(0, task.taskModel)
                _tasks.remove(task.code)
                println("Task for code '${task.code}' completed successfully.")
            } catch (e: Exception) {
                // 任务失败
                val errorMessage = "任务在执行过程中发生错误: ${e.message}"
                task.status = TaskStatus.Error(errorMessage)
                println("Task for code '${task.code}' failed: $errorMessage")
            } finally {
                // 任务结束（无论成功或失败），减少运行计数
                runningTaskCount.decrementAndGet()
                // 尝试调度下一个任务
                tryToScheduleNext()
            }
        }
    }
}

// 内部用于管理任务状态和进度的类
class Task(
    val code: String,
    val name: String,
    val tradeDate: String,
    var status: TaskStatus,
    val progressFlow: MutableStateFlow<TaskProgress> = MutableStateFlow(
        TaskProgress(0, actions.size, "任务待处理...", 0f)
    ),
    val actions: List<suspend () -> String>
) {
    fun updateProgress(
        step: Int,
        description: String,
        percentage: Float
    ) {
        progressFlow.value = TaskProgress(step, actions.size, description, percentage)
    }

    @OptIn(ExperimentalUuidApi::class)
    val taskModel: TaskModel
        get() = TaskModel(
            id = "",
            code = code,
            name = name,
            tradeDate = tradeDate,
            status = when (status) {
                is TaskStatus.Pending -> "Pending"
                is TaskStatus.Done -> "Done"
                is TaskStatus.Running -> "Running"
                is TaskStatus.Error -> "Error"
            },
            progress = Progress(
                step = progressFlow.value.step,
                totalStep = progressFlow.value.totalStep,
                description = progressFlow.value.description,
                percentage = progressFlow.value.progressPercentage
            )
        )
}

// 用于通过 WebSocket 传输的进度数据模型
data class TaskProgress(
    val step: Int,
    val totalStep: Int,
    val description: String,
    val progressPercentage: Float
)

// 定义任务的几种可能状态
sealed class TaskStatus {
    // 任务在队列中等待执行
    data object Pending : TaskStatus()

    // 任务正在运行，持有一个 StateFlow 以便客户端订阅进度更新
    data object Running : TaskStatus()

    // 任务已成功完成
    @Serializable
    data object Done : TaskStatus()

    // 任务失败
    @Serializable
    data class Error(val reason: String) : TaskStatus()
}

