package org.shiroumi.quant_kmp

import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.shiroumi.quant_kmp.ui.auth.AuthGate
import org.shiroumi.quant_kmp.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        initToastUtils(applicationContext)
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            splashScreenView.view.animate()
                .alpha(0f)
                .scaleX(1.04f)
                .scaleY(1.04f)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .withEndAction { splashScreenView.remove() }
                .start()
        }
        enableEdgeToEdge()

        setContent {
            // 根背景兜底已收敛进 AppTheme 内部的全屏 Surface，入口不再各自包 Surface。
            AppTheme {
                AuthGate()
            }
        }
    }
}
