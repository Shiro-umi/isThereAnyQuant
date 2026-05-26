package org.shiroumi.quant_kmp.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.shiroumi.config.AppConfig
import org.shiroumi.quant_kmp.feature.auth.AuthContract
import org.shiroumi.quant_kmp.feature.auth.AuthViewModel
import org.shiroumi.quant_kmp.feature.auth.LoginScreen
import org.shiroumi.quant_kmp.ui.core.viewmodel.LocalAuthViewModel
import org.shiroumi.quant_kmp.ui.navigation.Navigation
import kotlin.math.roundToInt

/**
 * 认证网关
 * 根据认证状态决定显示登录界面还是主应用
 */
@Composable
fun AuthGate(
    viewModel: AuthViewModel = viewModel { AuthViewModel() }
) {
    val state by viewModel.state.collectAsState()

    // 标记是否已完成首次认证检查
    var hasCheckedAuth by remember { mutableStateOf(false) }

    // 检查认证状态
    LaunchedEffect(Unit) {
        viewModel.checkAuthStatus()
        hasCheckedAuth = true
    }

    // 在首次检查完成前，始终显示 Loading，避免"闪一下登录页"。
    // Error 与 Unauthenticated 都渲染 LoginScreen，归一为同一目标，避免登录失败时整页重建导致用户输入被清空。
    val displayStage: AuthStage = when {
        !hasCheckedAuth || state.isLoading -> AuthStage.Loading
        state.authStatus == AuthContract.AuthStatus.Authenticated -> AuthStage.Authenticated
        else -> AuthStage.Login
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalAuthViewModel provides viewModel) {
            AnimatedContent(
                targetState = displayStage,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "AuthTransition"
            ) { stage ->
                when (stage) {
                    AuthStage.Loading -> LoadingScreen()
                    AuthStage.Authenticated -> Navigation()
                    AuthStage.Login -> LoginScreen(onLoginSuccess = {})
                }
            }
        }

        if (AppConfig.mode == "debug" || AppConfig.mode == "debug-wan") {
            ScreenSizeDebugBadge(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun ScreenSizeDebugBadge(
    modifier: Modifier = Modifier,
) {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val size = windowInfo.containerSize
    val widthDp = with(density) { size.width.toDp().value }.roundToInt()
    val heightDp = with(density) { size.height.toDp().value }.roundToInt()

    Surface(
        modifier = modifier.shadow(
            elevation = 8.dp,
            shape = MaterialTheme.shapes.small,
            clip = false
        ),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.86f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = "${widthDp}dp x ${heightDp}dp",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private enum class AuthStage { Loading, Login, Authenticated }

/**
 * 加载界面
 */
@Composable
private fun LoadingScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}
