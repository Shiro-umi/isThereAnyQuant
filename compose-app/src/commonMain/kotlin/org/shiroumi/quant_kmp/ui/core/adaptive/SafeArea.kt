package org.shiroumi.quant_kmp.ui.core.adaptive

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

/**
 * 安全区域内边距
 * 
 * 用于适配刘海屏、灵动岛、圆角屏幕等
 * 在 Web 平台上，这些值由 CSS env(safe-area-inset-*) 提供
 */
@Stable
data class SafeAreaInsets(
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val left: Float = 0f
) {
    /**
     * 转换为 PaddingValues
     */
    fun toPaddingValues(): PaddingValues = PaddingValues(
        start = left.dp,
        top = top.dp,
        end = right.dp,
        bottom = bottom.dp
    )
    
    /**
     * 仅顶部内边距
     */
    fun topOnly(): PaddingValues = PaddingValues(top = top.dp)
    
    /**
     * 仅底部内边距
     */
    fun bottomOnly(): PaddingValues = PaddingValues(bottom = bottom.dp)
    
    /**
     * 水平方向内边距
     */
    fun horizontalOnly(): PaddingValues = PaddingValues(
        start = left.dp,
        end = right.dp
    )
}

/**
 * 获取安全区域内边距
 * 
 * 在 Web 平台上，这些值由浏览器通过 CSS env() 函数提供
 * 在原生平台上，这些值由系统提供
 */
@Composable
expect fun rememberSafeAreaInsets(): SafeAreaInsets

/**
 * 应用安全区域的内边距修饰符
 * 
 * 使用示例：
 * ```
 * Box(
 *     modifier = Modifier
 *         .fillMaxSize()
 *         .safeAreaPadding()
 * ) {
 *     // 内容
 * }
 * ```
 */
@Composable
fun safeAreaPadding(): PaddingValues {
    val insets = rememberSafeAreaInsets()
    return remember(insets) { insets.toPaddingValues() }
}

/**
 * 仅顶部安全区域内边距
 * 用于顶部导航栏
 */
@Composable
fun safeAreaTopPadding(): PaddingValues {
    val insets = rememberSafeAreaInsets()
    return remember(insets) { insets.topOnly() }
}

/**
 * 仅底部安全区域内边距
 * 用于底部导航栏
 */
@Composable
fun safeAreaBottomPadding(): PaddingValues {
    val insets = rememberSafeAreaInsets()
    return remember(insets) { insets.bottomOnly() }
}

/**
 * 水平方向安全区域内边距
 * 用于防止内容被圆角屏幕裁剪
 */
@Composable
fun safeAreaHorizontalPadding(): PaddingValues {
    val insets = rememberSafeAreaInsets()
    return remember(insets) { insets.horizontalOnly() }
}
