package org.shiroumi.quant_kmp.ui.animation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

// =============================================================================
// 动画常量定义
// =============================================================================

object AnimationDurations {
    const val FAST = 150
    const val NORMAL = 300
    const val SLOW = 500
    const val VERY_SLOW = 800
}

object AnimationDelays {
    const val NONE = 0
    const val SHORT = 50
    const val NORMAL = 100
    const val LONG = 200
}

// =============================================================================
// 淡入动画 - 从透明到不透明
// =============================================================================

@Composable
fun FadeInAnimation(
    visible: Boolean,
    delayMillis: Int = AnimationDelays.NONE,
    durationMillis: Int = AnimationDurations.NORMAL,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = FastOutSlowInEasing
            )
        ),
        exit = fadeOut(
            animationSpec = tween(
                durationMillis = AnimationDurations.FAST,
                easing = LinearOutSlowInEasing
            )
        )
    ) {
        content()
    }
}

// =============================================================================
// 滑入动画 - 从下方滑入
// =============================================================================

@Composable
fun SlideInFromBottomAnimation(
    visible: Boolean,
    delayMillis: Int = AnimationDelays.NONE,
    durationMillis: Int = AnimationDurations.NORMAL,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it / 4 },
            animationSpec = tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = FastOutSlowInEasing
            )
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = FastOutSlowInEasing
            )
        ),
        exit = slideOutVertically(
            targetOffsetY = { it / 4 },
            animationSpec = tween(
                durationMillis = AnimationDurations.FAST,
                easing = LinearOutSlowInEasing
            )
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = AnimationDurations.FAST,
                easing = LinearOutSlowInEasing
            )
        )
    ) {
        content()
    }
}

// =============================================================================
// 滑入动画 - 从左侧滑入
// =============================================================================

@Composable
fun SlideInFromLeftAnimation(
    visible: Boolean,
    delayMillis: Int = AnimationDelays.NONE,
    durationMillis: Int = AnimationDurations.NORMAL,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            initialOffsetX = { -it / 3 },
            animationSpec = tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = FastOutSlowInEasing
            )
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = FastOutSlowInEasing
            )
        ),
        exit = fadeOut(
            animationSpec = tween(
                durationMillis = AnimationDurations.FAST,
                easing = LinearOutSlowInEasing
            )
        )
    ) {
        content()
    }
}

// =============================================================================
// 缩放动画 - 从小变大
// =============================================================================

@Composable
fun ScaleInAnimation(
    visible: Boolean,
    delayMillis: Int = AnimationDelays.NONE,
    durationMillis: Int = AnimationDurations.NORMAL,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            initialScale = 0.8f,
            animationSpec = tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = FastOutSlowInEasing
            )
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = FastOutSlowInEasing
            )
        ),
        exit = scaleOut(
            targetScale = 0.8f,
            animationSpec = tween(
                durationMillis = AnimationDurations.FAST,
                easing = LinearOutSlowInEasing
            )
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = AnimationDurations.FAST,
                easing = LinearOutSlowInEasing
            )
        )
    ) {
        content()
    }
}

// =============================================================================
// 展开动画 - 垂直展开
// =============================================================================

@Composable
fun ExpandVerticallyAnimation(
    visible: Boolean,
    delayMillis: Int = AnimationDelays.NONE,
    durationMillis: Int = AnimationDurations.NORMAL,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = FastOutSlowInEasing
            )
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = FastOutSlowInEasing
            )
        ),
        exit = shrinkVertically(
            animationSpec = tween(
                durationMillis = AnimationDurations.FAST,
                easing = LinearOutSlowInEasing
            )
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = AnimationDurations.FAST,
                easing = LinearOutSlowInEasing
            )
        )
    ) {
        content()
    }
}

// =============================================================================
// 交错动画容器 - 子元素依次出现
// =============================================================================

@Composable
fun StaggeredAnimationContainer(
    visible: Boolean,
    baseDelayMillis: Int = AnimationDelays.NONE,
    staggerDelayMillis: Int = AnimationDelays.SHORT,
    content: @Composable StaggeredAnimationScope.() -> Unit
) {
    val scope = StaggeredAnimationScope(
        visible = visible,
        baseDelayMillis = baseDelayMillis,
        staggerDelayMillis = staggerDelayMillis
    )
    scope.content()
}

class StaggeredAnimationScope(
    private val visible: Boolean,
    private val baseDelayMillis: Int,
    private val staggerDelayMillis: Int
) {
    private var currentIndex = 0
    
    @Composable
    fun animatedItem(content: @Composable () -> Unit) {
        val delay = baseDelayMillis + (currentIndex * staggerDelayMillis)
        currentIndex++
        
        SlideInFromBottomAnimation(
            visible = visible,
            delayMillis = delay,
            durationMillis = AnimationDurations.NORMAL
        ) {
            content()
        }
    }
    
    @Composable
    fun animatedItemWithScale(content: @Composable () -> Unit) {
        val delay = baseDelayMillis + (currentIndex * staggerDelayMillis)
        currentIndex++
        
        ScaleInAnimation(
            visible = visible,
            delayMillis = delay,
            durationMillis = AnimationDurations.NORMAL
        ) {
            content()
        }
    }
}

// =============================================================================
// 脉冲动画 - 用于吸引注意力
// =============================================================================

@Composable
fun PulsingAnimation(
    content: @Composable (scale: Float, alpha: Float) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    content(scale, alpha)
}

// =============================================================================
// 摇晃动画 - 用于错误提示
// =============================================================================

@Composable
fun ShakeAnimation(
    shake: Boolean,
    content: @Composable () -> Unit
) {
    val shakeOffset by animateFloatAsState(
        targetValue = if (shake) 10f else 0f,
        animationSpec = if (shake) {
            keyframes {
                durationMillis = 400
                0f at 0
                (-10f) at 50
                10f at 100
                (-10f) at 150
                10f at 200
                (-5f) at 250
                5f at 300
                0f at 350
            }
        } else {
            tween(0)
        },
        label = "shake"
    )
    
    Box(
        modifier = Modifier.graphicsLayer {
            translationX = shakeOffset
        }
    ) {
        content()
    }
}

// =============================================================================
// 数字滚动动画
// =============================================================================

@Composable
fun AnimatedCounter(
    targetValue: Int,
    durationMillis: Int = AnimationDurations.SLOW,
    content: @Composable (displayValue: Int) -> Unit
) {
    val animatedValue by animateIntAsState(
        targetValue = targetValue,
        animationSpec = tween(
            durationMillis = durationMillis,
            easing = FastOutSlowInEasing
        ),
        label = "counter"
    )
    
    content(animatedValue)
}

// =============================================================================
// 骨架屏闪烁动画
// =============================================================================

@Composable
fun ShimmerAnimation(
    content: @Composable (progress: Float) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()
    
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    
    content(progress)
}
