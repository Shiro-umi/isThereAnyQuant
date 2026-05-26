package org.shiroumi.agent.util

/**
 * 进程树工具类
 */
object ProcessTreeUtil {
    /**
     * 递归收集进程树中的所有 PID（包括自己和所有后代）
     *
     * @param pid 根进程 PID
     * @return 进程树中所有 PID 的列表（包括根进程自己）
     */
    fun collectProcessTree(pid: Long): List<Long> {
        val result = mutableListOf<Long>()
        val handle = ProcessHandle.of(pid).orElse(null) ?: return result

        fun collect(h: ProcessHandle) {
            result.add(h.pid())
            h.children().forEach { collect(it) }
        }

        collect(handle)
        return result
    }
}
