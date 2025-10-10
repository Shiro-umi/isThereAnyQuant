package org.shiroumi.quant_kmp.ui.structure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import io.ktor.utils.io.ioDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.shiroumi.quant_kmp.ui.theme.AppTypography
import org.shiroumi.quant_kmp.ui.theme.darkScheme
import org.shiroumi.quant_kmp.ui.theme.montserratFontFamily

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun App() {
    val typography = AppTypography()
    val fontFamily = montserratFontFamily()
    MaterialTheme(
        colorScheme = darkScheme,
        typography = typography
    ) {
        var isFontReady by remember { mutableStateOf(false) }

        val fontFamilyResolver = LocalFontFamilyResolver.current

        LaunchedEffect(Unit) {
            // 在 IO 调度器上执行，避免阻塞主线程
            launch(ioDispatcher()) {
                fontFamilyResolver.resolve(fontFamily)
                delay(1000)
                isFontReady = true
            }
        }

        AnimatedVisibility(isFontReady, enter = fadeIn(), exit = fadeOut()) {
            NavigationPage()
        }

        AnimatedVisibility(!isFontReady, enter = fadeIn(), exit = fadeOut()) {
            Surface {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}