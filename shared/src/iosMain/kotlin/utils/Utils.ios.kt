package utils

import platform.Foundation.NSProcessInfo

/**
 * iOS 平台的逻辑核心数。
 *
 * 通过 [NSProcessInfo] 暴露的 processorCount 读取设备核心数，
 * 与 JVM 的 availableProcessors、JS 的 navigator.hardwareConcurrency 对齐，
 * 供并行计算调度（如策略跟踪流转边布局）确定并发度。
 */
actual val cpuCores: Int
    get() = NSProcessInfo.processInfo.processorCount.toInt()
