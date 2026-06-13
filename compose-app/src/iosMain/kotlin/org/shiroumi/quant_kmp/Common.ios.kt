package org.shiroumi.quant_kmp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.Modifier
import io.kamel.core.utils.File
import io.ktor.util.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.FileSystem
import org.shiroumi.quant_kmp.ui.components.ToastHost
import org.shiroumi.quant_kmp.ui.components.ToastHostState
import kotlin.math.round

@Suppress("NO_ACTUAL_FOR_EXPECT")
actual fun getPlatform(): Platform {
    // Platform 在非 JS 平台上为 internal，与 Android actual 保持同一占位策略；
    // 该返回值不参与业务分支判断，业务平台识别走 isAndroidPlatform()。
    return Platform.Js(Platform.JsPlatform.Browser)
}

private val toastState: ToastHostState by lazy {
    ToastHostState()
}

private val systemFileSystem: FileSystem by lazy {
    FileSystem.SYSTEM
}

@Composable
actual fun MultiPlatform(
    vararg values: ProvidedValue<*>,
    content: @Composable (BoxScope.() -> Unit)
) = Box(modifier = Modifier.fillMaxSize()) {
    CompositionLocalProvider(*values) {
        content()
        // iOS 无系统级 Toast，使用与 Web 一致的 Compose 自绘全局 host。
        ToastHost(hostState = toastState)
    }
}

actual fun showToast(msg: String) {
    CoroutineScope(Dispatchers.Main).launch {
        toastState.showToast(msg)
    }
}

actual fun getFileSystem() = systemFileSystem

actual suspend fun getKamelFile(path: String): File {
    // iOS 上 Kamel File 接受本地文件路径字符串，与 Android actual 一致。
    return File(path)
}

actual fun Float.roundToString(): String {
    // 与 Web actual 同口径手动保留一位小数；禁用 String.format（JS 不兼容，全平台统一手写）。
    val rounded = round(this * 10f) / 10f
    val text = rounded.toString()
    return if (text.contains(".")) {
        val decimals = text.substringAfter(".")
        if (decimals.isEmpty()) "${text}0" else text
    } else {
        "$text.0"
    }
}
