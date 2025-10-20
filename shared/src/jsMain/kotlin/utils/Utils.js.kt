package utils

import kotlinx.browser.window

actual val cpuCores: Int
    get() = window.navigator.hardwareConcurrency.toInt()