@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.shiroumi.quant_kmp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.kamel.core.utils.File
import io.ktor.util.Platform
import io.ktor.utils.io.ioDispatcher
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.fakefilesystem.FakeFileSystem
import org.shiroumi.quant_kmp.ui.components.ToastHost
import org.shiroumi.quant_kmp.ui.components.ToastHostState
import kotlin.js.Promise
import kotlin.math.round

private fun fetchBrowserFile(path: String): Promise<org.w3c.files.File> = js(
    "fetch(path).then(response => response.blob()).then(blob => new File([blob], path))"
)

/**
 * 本地组合键：PWA 模式
 */
val LocalIsPwaMode = staticCompositionLocalOf { false }

/**
 * 检测是否在 PWA 模式下运行
 */
fun isPwaMode(): Boolean {
    return try {
        document.documentElement?.classList?.contains("pwa-standalone") == true
    } catch (e: Exception) {
        false
    }
}

actual fun getPlatform(): Platform {
    return Platform.Js(Platform.JsPlatform.Browser)
}

private val toastState: ToastHostState by lazy {
    ToastHostState()
}

private val fakeFileSystem: FileSystem by lazy {
    FakeFileSystem()
}

@Composable
actual fun MultiPlatform(
    vararg values: ProvidedValue<*>,
    content: @Composable (BoxScope.() -> Unit)
) = Box(modifier = Modifier.fillMaxSize()) {
    CompositionLocalProvider(
        LocalIsPwaMode provides isPwaMode(),
        *values
    ) {
        content()
        ToastHost(hostState = toastState)
    }
}

actual fun showToast(msg: String) {
    CoroutineScope(Dispatchers.Default).launch {
        toastState.showToast(msg)
    }
}


actual fun getFileSystem() = fakeFileSystem

actual suspend fun getKamelFile(path: String): File {
    return File(fetchBrowserFile(path).await())
}

actual fun Float.roundToString(): String {
    val rounded = round(this * 10f) / 10f
    val text = rounded.toString()
    return if (text.contains(".")) {
        val decimals = text.substringAfter(".")
        if (decimals.isEmpty()) "${text}0" else text
    } else {
        "$text.0"
    }
}
