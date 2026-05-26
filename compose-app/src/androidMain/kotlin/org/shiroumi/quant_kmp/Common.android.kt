package org.shiroumi.quant_kmp

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.kamel.core.utils.File
import io.ktor.util.Platform
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import java.io.File as JavaFile

/**
 * 本地组合键：是否为平板模式
 */
val LocalIsTabletMode = staticCompositionLocalOf { false }

/**
 * 检测是否在平板模式下运行
 */
fun isTabletMode(): Boolean {
    // Android 设备默认为 false，将在 MultiPlatform 中根据配置设置
    return false
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
actual fun getPlatform(): Platform {
    // Platform 在 Android 上为 internal，使用 Js 作为替代
    return Platform.Js(Platform.JsPlatform.Browser)
}

private val systemFileSystem: FileSystem by lazy {
    FileSystem.SYSTEM
}

@Composable
actual fun MultiPlatform(
    vararg values: ProvidedValue<*>,
    content: @Composable (BoxScope.() -> Unit)
) = Box(modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    CompositionLocalProvider(
        LocalIsTabletMode provides isTabletMode(),
        *values
    ) {
        content()
    }
}

// 用于存储 Application Context 以在非 Composable 中使用
internal var applicationContext: Context? = null

/**
 * 初始化 Toast 工具类
 * 应在 Application.onCreate 中调用
 */
fun initToastUtils(context: Context) {
    applicationContext = context.applicationContext
}

actual fun showToast(msg: String) {
    val context = applicationContext
    if (context != null) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context,
                msg,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    } else {
        Log.w("ToastUtils", "Application context not initialized. Call initToastUtils() first.")
    }
}

actual fun getFileSystem() = systemFileSystem

actual suspend fun getKamelFile(path: String): File {
    // Android 上的文件处理 - 使用 Kamel 的 File 支持
    // Kamel File 在 Android 上接受文件路径字符串
    return File(path)
}

actual fun Float.roundToString(): String {
    return String.format("%.1f", this)
}
