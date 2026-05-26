package utils

import kotlin.JsFun
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => Math.max(1, navigator.hardwareConcurrency || 1)")
private external fun browserHardwareConcurrency(): Int

actual val cpuCores: Int
    get() = browserHardwareConcurrency()
