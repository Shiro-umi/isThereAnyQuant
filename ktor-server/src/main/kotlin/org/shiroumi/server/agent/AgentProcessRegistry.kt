package org.shiroumi.server.agent

import utils.logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger by logger("AgentProcessRegistry")

/**
 * Agent 子进程注册表，用于跟踪所有 Claude 进程及其子孙进程树。
 *
 * 职责：
 * 1. 注册进程启动时的 pid/workDir
 * 2. 持久化到 PID 文件（用于启动清扫）
 * 3. 提供统一的关停入口（递归杀掉整个进程树）
 */
object AgentProcessRegistry {

    private data class ProcessEntry(
        val sessionId: String,
        val pid: Long,
        val workDir: String,
        val startedAt: Long = System.currentTimeMillis(),
        @Volatile var terminated: Boolean = false
    )

    private val registry = ConcurrentHashMap<String, ProcessEntry>()

    private val pidDir = File(
        System.getProperty("user.home"),
        ".quant_agents/runtime/pids"
    ).also { it.mkdirs() }

    /**
     * 递归收集进程树中的所有 PID（包括自己和所有后代）
     */
    private fun collectProcessTree(pid: Long): List<Long> {
        val result = mutableListOf<Long>()
        val handle = ProcessHandle.of(pid).orElse(null) ?: return result

        fun collect(h: ProcessHandle) {
            result.add(h.pid())
            h.children().forEach { collect(it) }
        }

        collect(handle)
        return result
    }

    /**
     * 注册一个新启动的进程。
     */
    fun register(sessionId: String, pid: Long, workDir: String) {
        val entry = ProcessEntry(sessionId, pid, workDir)
        registry[sessionId] = entry

        val pidFile = File(pidDir, "$sessionId.pid")
        try {
            pidFile.writeText("$pid\n${entry.startedAt}\n$workDir\n")
            logger.info("[Registry] ✔ registered sessionId=$sessionId pid=$pid")
        } catch (e: Exception) {
            logger.error("[Registry] ✘ failed to write PID file: ${pidFile.absolutePath} - ${e.message}")
        }
    }

    /**
     * 反注册进程（进程自然退出时调用）。
     */
    fun unregister(sessionId: String) {
        val entry = registry.remove(sessionId)
        if (entry != null) {
            entry.terminated = true
            val pidFile = File(pidDir, "$sessionId.pid")
            pidFile.delete()
            logger.info("[Registry] ✔ unregistered sessionId=$sessionId pid=${entry.pid}")
        }
    }

    /**
     * 关停所有已注册的进程（递归杀掉整个进程树）。
     */
    fun shutdownAll(graceMillis: Long = 5000) {
        val entries = registry.values.filter { !it.terminated }
        if (entries.isEmpty()) {
            logger.info("[Registry] shutdownAll: no active processes")
            return
        }

        logger.info("[Registry] shutdownAll: terminating ${entries.size} process(es), grace=${graceMillis}ms")

        // 第一轮：收集所有进程树，发 SIGTERM
        val allPids = mutableSetOf<Long>()
        entries.forEach { entry ->
            val tree = collectProcessTree(entry.pid)
            allPids.addAll(tree)
            logger.info("[Registry] process tree for session=${entry.sessionId}: ${tree.joinToString(",")} (${tree.size} process(es))")
        }

        allPids.forEach { pid ->
            try {
                Runtime.getRuntime().exec(arrayOf("kill", "-TERM", pid.toString()))
            } catch (e: Exception) {
                logger.error("[Registry] failed to send SIGTERM to pid=$pid - ${e.message}")
            }
        }

        logger.info("[Registry] sent SIGTERM to ${allPids.size} process(es), waiting ${graceMillis}ms...")

        // 并发等待优雅期（短轮询，避免阻塞主线程过久）
        val deadline = System.currentTimeMillis() + graceMillis.coerceAtLeast(1000)
        while (System.currentTimeMillis() < deadline) {
            val anyAlive = allPids.any { pid ->
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false) == true
            }
            if (!anyAlive) {
                logger.info("[Registry] all processes terminated before grace period")
                break
            }
            Thread.sleep(100)
        }

        // 第二轮：检查仍存活的进程，发 SIGKILL
        val stillAlive = allPids.filter { pid ->
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false) == true
        }

        if (stillAlive.isNotEmpty()) {
            logger.warning("[Registry] ${stillAlive.size} process(es) still alive after grace period, sending SIGKILL")
            stillAlive.forEach { pid ->
                try {
                    Runtime.getRuntime().exec(arrayOf("kill", "-KILL", pid.toString()))
                } catch (e: Exception) {
                    logger.error("[Registry] failed to send SIGKILL to pid=$pid - ${e.message}")
                }
            }
        }

        // 清理注册表和 PID 文件
        entries.forEach { entry ->
            entry.terminated = true
            File(pidDir, "${entry.sessionId}.pid").delete()
        }
        registry.clear()

        logger.info("[Registry] shutdownAll completed")
    }

    /**
     * 启动清扫：读取 PID 文件，杀掉残留的孤儿进程。
     */
    fun cleanupOrphans() {
        val pidFiles = pidDir.listFiles { f -> f.extension == "pid" } ?: emptyArray()
        if (pidFiles.isEmpty()) {
            logger.info("[Registry] cleanupOrphans: no PID files found")
            return
        }

        logger.info("[Registry] cleanupOrphans: found ${pidFiles.size} PID file(s)")

        pidFiles.forEach { file ->
            try {
                val lines = file.readLines()
                if (lines.isEmpty()) {
                    file.delete()
                    return@forEach
                }

                val pid = lines[0].toLongOrNull() ?: run {
                    logger.warning("[Registry] invalid pid in ${file.name}, deleting")
                    file.delete()
                    return@forEach
                }

                // 收集整个进程树
                val tree = collectProcessTree(pid)
                if (tree.isEmpty()) {
                    logger.info("[Registry] process tree for ${file.name} already dead")
                    file.delete()
                    return@forEach
                }

                // 检查根进程是否是 claude/node/npm
                val rootHandle = ProcessHandle.of(pid).orElse(null)
                if (rootHandle != null && rootHandle.isAlive) {
                    val command = rootHandle.info().command().orElse("") ?: ""
                    val isClaude = command.contains("claude") ||
                                   command.contains("node") ||
                                   command.contains("npm")

                    if (isClaude) {
                        logger.warning("[Registry] killing orphan tree from ${file.name}: ${tree.joinToString(",")} (${tree.size} process(es))")
                        tree.forEach { treePid: Long ->
                            try {
                                Runtime.getRuntime().exec(arrayOf("kill", "-KILL", treePid.toString()))
                            } catch (e: Exception) {
                                logger.error("[Registry] failed to kill pid=$treePid - ${e.message}")
                            }
                        }
                    }
                }

                file.delete()
            } catch (e: Exception) {
                logger.error("[Registry] error processing ${file.name} - ${e.message}")
            }
        }

        logger.info("[Registry] cleanupOrphans completed")
    }
}
