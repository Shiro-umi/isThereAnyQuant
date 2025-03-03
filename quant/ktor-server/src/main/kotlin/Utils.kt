package org.shiroumi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob

fun printProgressBar(total: Int, current: Int) = if (total == 0) {
    println("\r[....................] 0/0 (0%)")
} else {
    // 计算完成百分比
    val percent = current.toDouble() / total
    val completedChars = (percent * 20).toInt() // 假设总长度为20

    print("\r[")
    (0 until completedChars).forEach { _ ->
        print("=")
    }
    (completedChars..19).forEach { _ ->
        print(".")
    }
    print("] " + current + "/" + total + " (" + (percent * 100).toInt() + "%)")
    MainScope()
}

val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)