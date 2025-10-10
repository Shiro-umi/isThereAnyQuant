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
import kotlinx.browser.window
import kotlinx.coroutines.await
import okio.FileSystem
import okio.fakefilesystem.FakeFileSystem
import org.shiroumi.quant_kmp.toast.LocalToastHostState
import org.shiroumi.quant_kmp.toast.ToastHost
import org.shiroumi.quant_kmp.toast.ToastHostState
import org.shiroumi.quant_kmp.toast.rememberToastHostState

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
    values.toMutableList().add(LocalToastHostState provides toastState)
    CompositionLocalProvider(*values) {
        content()
        ToastHost(toastState)
    }
}

actual suspend fun showToast(msg: String) = toastState.showToast(msg)


actual fun getFileSystem() = fakeFileSystem

actual suspend fun getKamelFile(path: String): File {
    val blob = window.fetch(path).await().blob().await()
    println(blob.toString())
    return File(
        org.w3c.files.File(
            arrayOf(blob), path
        )
    )
}

actual fun Float.roundToString(): String {
    return this.asDynamic().toFixed(1) as String
}