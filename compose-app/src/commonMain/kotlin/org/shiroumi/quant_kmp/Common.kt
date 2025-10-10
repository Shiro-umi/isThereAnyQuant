package org.shiroumi.quant_kmp

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import io.kamel.core.utils.File
import io.ktor.util.Platform
import okio.FileSystem

expect fun getPlatform(): Platform

@Composable
expect fun MultiPlatform(
    vararg values: ProvidedValue<*>,
    content: @Composable BoxScope.() -> Unit
)

expect suspend fun showToast(msg: String)

expect fun getFileSystem(): FileSystem

expect suspend fun getKamelFile(path: String): File

expect fun Float.roundToString(): String