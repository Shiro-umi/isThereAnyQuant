@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.shiroumi.quant_kmp.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 手机版（Compact）TitleBar 抽象。
 *
 * 各页面通过 [ProvideMobileTitleBar] 注册自己的标题与右侧动作；
 * 返回按钮由 [MobileNavTitleBar] 根据 navigation 栈是否可回退（栈深度 > 1）自动呈现。
 *
 * 控制器仅在 Compact 布局下注入；其它尺寸下 [LocalMobileTitleBarController]
 * 为 null，[ProvideMobileTitleBar] 自动 no-op，页面无需感知当前尺寸。
 */
data class MobileTitleBarSpec(
    val title: String,
    val subtitle: String? = null,
    val onBack: (() -> Unit)? = null,
    val actions: @Composable RowScope.() -> Unit = {},
    val large: Boolean = false,
    val scrollBehavior: TopAppBarScrollBehavior? = null,
    val titleMotion: MobileTitleMotion = MobileTitleMotion.None,
    /** 紧贴标题文字右侧的附属控件（如说明叹号），与标题同处一行而非右侧 actions 区。 */
    val titleTrailing: (@Composable () -> Unit)? = null,
)

enum class MobileTitleMotion {
    None,
    StaggeredHorizontal,
}

@Stable
class MobileTitleBarController internal constructor() {
    var spec: MobileTitleBarSpec? by mutableStateOf(null)
        private set

    internal fun setSpec(value: MobileTitleBarSpec) {
        spec = value
    }

    internal fun clearIf(value: MobileTitleBarSpec) {
        if (spec === value) spec = null
    }
}

val LocalMobileTitleBarController = staticCompositionLocalOf<MobileTitleBarController?> { null }

@Composable
fun rememberMobileTitleBarController(): MobileTitleBarController =
    remember { MobileTitleBarController() }

/**
 * 在页面顶层调用，向手机版 TitleBar 注册标题与动作。
 * 仅在 Compact 布局下生效，其它尺寸下为 no-op。
 */
@Composable
fun ProvideMobileTitleBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    titleMotion: MobileTitleMotion = MobileTitleMotion.None,
    titleTrailing: (@Composable () -> Unit)? = null,
) {
    val controller = LocalMobileTitleBarController.current ?: return
    DisposableEffect(controller, title, subtitle, onBack, titleMotion) {
        val spec = MobileTitleBarSpec(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            actions = actions,
            titleMotion = titleMotion,
            titleTrailing = titleTrailing,
        )
        controller.setSpec(spec)
        onDispose { controller.clearIf(spec) }
    }
}

@Composable
fun ProvideScrollableLargeMobileTitleBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val controller = LocalMobileTitleBarController.current ?: return
    DisposableEffect(controller, title, subtitle, onBack, scrollBehavior.state) {
        val spec = MobileTitleBarSpec(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            actions = actions,
            large = true,
            scrollBehavior = scrollBehavior,
            titleMotion = MobileTitleMotion.None,
        )
        controller.setSpec(spec)
        onDispose { controller.clearIf(spec) }
    }
}

/**
 * 手机版统一 TopAppBar：始终显示。
 *
 * - 标题 / 右侧 page-actions 由当前页面通过 [ProvideMobileTitleBar] 注册；
 *   未注册时使用 [defaultTitle]。
 * - [canGoBack] 为 true 时显示返回按钮（仅在二级页面出现）。
 * - [globalActions] 由 navigation 层固定提供（数据更新状态、用户菜单等）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileNavTitleBar(
    canGoBack: Boolean,
    onBack: () -> Unit,
    defaultTitle: String,
    globalActions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    agentExpanded: Boolean = false,
    onAgentCollapse: () -> Unit = {},
) {
    val controller = LocalMobileTitleBarController.current
    val spec = controller?.spec
    val title = spec?.title ?: defaultTitle
    val subtitle = spec?.subtitle
    val effectiveOnBack: (() -> Unit)? = when {
        agentExpanded -> onAgentCollapse
        spec?.onBack != null -> spec.onBack
        canGoBack -> onBack
        else -> null
    }

    val collapsedFraction = spec?.scrollBehavior?.state?.collapsedFraction ?: 1f
    val titleStartPadding = if (effectiveOnBack != null) 48.dp else 0.dp
    val titleContent: @Composable () -> Unit = {
        MobileTitleBarTitle(
            title = title,
            subtitle = subtitle,
            large = spec?.large == true,
            collapsedFraction = collapsedFraction,
            titleMotion = spec?.titleMotion ?: MobileTitleMotion.None,
            startPadding = titleStartPadding,
            titleTrailing = spec?.titleTrailing,
        )
    }
    val actionsContent: @Composable RowScope.() -> Unit = {
        spec?.actions?.invoke(this)
        globalActions()
    }

    // 状态栏避让统一由外层 Box 的 statusBarsPadding 处理，topBar 自身禁用 windowInsets。
    // 否则在 enableEdgeToEdge() 下，TopAppBar 默认会把 statusBar inset 叠加进固定容器高度
    // （容器变成 statusBar + 64dp，inset 段是空白色带），表现为「标题下方/周围多一段空白」。
    // 收敛后：topBar 容器回归标准高度，statusBar 区由根背景自然延伸，标题与返回键整体下移对齐。
    if (spec?.large == true) {
        Box(modifier = modifier.statusBarsPadding()) {
            LargeTopAppBar(
                title = titleContent,
                navigationIcon = {},
                actions = actionsContent,
                scrollBehavior = spec.scrollBehavior,
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            AnimatedBackButton(
                onBack = effectiveOnBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 4.dp, top = 8.dp),
            )
        }
    } else {
        Box(modifier = modifier.statusBarsPadding()) {
            TopAppBar(
                title = titleContent,
                navigationIcon = {},
                actions = actionsContent,
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            AnimatedBackButton(
                onBack = effectiveOnBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun AnimatedBackButton(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = onBack != null,
        modifier = modifier,
        enter = slideInHorizontally(
            initialOffsetX = { -it / 2 },
            animationSpec = tween(
                durationMillis = 220,
                easing = FastOutSlowInEasing,
            )
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = 180,
                easing = FastOutSlowInEasing,
            )
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { -it / 2 },
            animationSpec = tween(
                durationMillis = 160,
                easing = FastOutSlowInEasing,
            )
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = 140,
                easing = FastOutSlowInEasing,
            )
        ),
    ) {
        IconButton(onClick = { onBack?.invoke() }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
            )
        }
    }
}

@Composable
private fun MobileTitleBarTitle(
    title: String,
    subtitle: String?,
    large: Boolean,
    collapsedFraction: Float,
    titleMotion: MobileTitleMotion,
    startPadding: Dp,
    titleTrailing: (@Composable () -> Unit)? = null,
) {
    if (large) {
        // large 形态有展开/收起叠层动画，trailing 暂不参与；本项目 large 页面均未使用 titleTrailing。
        Box(modifier = Modifier.padding(start = startPadding)) {
            LargeMobileTitleBarTitle(
                title = title,
                subtitle = subtitle,
                collapsedFraction = collapsedFraction,
            )
        }
    } else if (subtitle.isNullOrBlank()) {
        Row(
            modifier = Modifier.padding(start = startPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 标题占剩余宽度并在过长时 ellipsis，titleTrailing 始终按本征宽度占位，
            // 不会被长标题挤成零宽（fill = false 让短标题不强行撑满，trailing 紧贴标题尾）。
            MobileTitleText(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                motion = titleMotion,
                modifier = Modifier.weight(1f, fill = false),
            )
            titleTrailing?.invoke()
        }
    } else {
        Row(
            modifier = Modifier.padding(start = startPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                MobileTitleText(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    motion = titleMotion,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            titleTrailing?.invoke()
        }
    }
}

@Composable
private fun MobileTitleText(
    text: String,
    style: TextStyle,
    fontWeight: FontWeight,
    motion: MobileTitleMotion,
    modifier: Modifier = Modifier,
) {
    when (motion) {
        MobileTitleMotion.None -> Text(
            text = text,
            modifier = modifier,
            style = style,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Staggered 是整行逐字标题动画，内部自铺满整行，不与 titleTrailing 共存（本项目无此组合），
        // 故不接收外部 weight modifier。
        MobileTitleMotion.StaggeredHorizontal -> StaggeredMobileTitleText(
            text = text,
            style = style,
            fontWeight = fontWeight,
        )
    }
}

@Composable
private fun StaggeredMobileTitleText(
    text: String,
    style: TextStyle,
    fontWeight: FontWeight,
) {
    val characterDelayMillis = 18
    val exitDurationMillis = 140
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            val transitionDuration = maxOf(initialState.length, targetState.length) *
                    characterDelayMillis + 220
            fadeIn(animationSpec = tween(durationMillis = 1)) togetherWith
                    fadeOut(animationSpec = tween(durationMillis = transitionDuration)) using
                    SizeTransform(clip = false)
        },
        label = "mobile_title_text"
    ) { animatedText ->
        val visibleState = remember(animatedText) {
            MutableTransitionState(false)
        }
        LaunchedEffect(animatedText, text) {
            visibleState.targetState = animatedText == text && animatedText.isNotBlank()
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            animatedText.forEachIndexed { index, character ->
                val enterDelay = index * characterDelayMillis
                val exitDelay = (animatedText.lastIndex - index).coerceAtLeast(0) * characterDelayMillis
                AnimatedVisibility(
                    visibleState = visibleState,
                    enter = slideInHorizontally(
                        initialOffsetX = { it / 2 },
                        animationSpec = tween(
                            durationMillis = 220,
                            delayMillis = enterDelay,
                            easing = FastOutSlowInEasing,
                        )
                    ) + fadeIn(
                        animationSpec = tween(
                            durationMillis = 180,
                            delayMillis = enterDelay,
                            easing = FastOutSlowInEasing,
                        )
                    ),
                    exit = slideOutHorizontally(
                        targetOffsetX = { it / 2 },
                        animationSpec = tween(
                            durationMillis = exitDurationMillis,
                            delayMillis = exitDelay,
                            easing = FastOutSlowInEasing,
                        )
                    ) + fadeOut(
                        animationSpec = tween(
                            durationMillis = exitDurationMillis,
                            delayMillis = exitDelay,
                            easing = FastOutSlowInEasing,
                        )
                    ),
                ) {
                    Text(
                        text = character.toString(),
                        style = style,
                        fontWeight = fontWeight,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

@Composable
private fun LargeMobileTitleBarTitle(
    title: String,
    subtitle: String?,
    collapsedFraction: Float,
) {
    val collapsedAlpha = collapsedFraction.coerceIn(0f, 1f)
    val expandedAlpha = 1f - collapsedAlpha

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier.graphicsLayer { alpha = expandedAlpha }
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = title,
            modifier = Modifier.graphicsLayer { alpha = collapsedAlpha },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
