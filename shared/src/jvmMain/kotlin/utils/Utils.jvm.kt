package utils

actual val cpuCores: Int
    get() = Runtime.getRuntime().availableProcessors()