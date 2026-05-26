package org.shiroumi.quant_kmp.ui.agent.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Agent 快捷入口专用设计规范
 *
 * 基于 Material 3，打造优雅精致的 AI 助手界面
 */
object AgentTheme {

    /**
     * 间距系统 - 8dp 网格
     */
    object Spacing {
        val xs = 4.dp
        val sm = 8.dp
        val md = 16.dp
        val lg = 24.dp
        val xl = 32.dp
        val xxl = 48.dp

        // 组件专用间距
        val bubblePadding = 12.dp
        val sectionGap = 16.dp
        val cardPadding = 16.dp
    }

    /**
     * 形状系统
     */
    object Shapes {
        val none = 0.dp
        val extraSmall = 4.dp
        val small = 8.dp
        val medium = 12.dp
        val large = 16.dp
        val extraLarge = 24.dp
        val full = 1000.dp // 圆形/ pill

        // 气泡专用
        val bubbleRadius = 18.dp
        val bubbleRadiusSmall = 12.dp
    }

    /**
     * 海拔系统 - 使用 Material 3 的 Tonal Elevation
     */
    object Elevation {
        val level0 = 0.dp
        val level1 = 1.dp  // 状态栏、分隔线
        val level2 = 3.dp  // 输入框、卡片
        val level3 = 6.dp  // 悬浮按钮、模态
    }

    /**
     * 内容区域尺寸
     */
    object Sizing {
        // 消息气泡最大宽度（相对于屏幕）
        val bubbleMaxWidthRatio = 0.85f

        // 输入框高度
        val inputMinHeight = 56.dp
        val inputMaxHeight = 200.dp

        // 状态栏高度
        val statusBarHeight = 56.dp

        // 侧边面板宽度（Medium/Expanded）
        val sidePanelWidthMin = 240.dp
        val sidePanelWidthMax = 320.dp

        // 工具调用指示器
        val toolIndicatorHeight = 48.dp
    }

    /**
     * 动画时长
     */
    object Durations {
        val instant = 50
        val fast = 150
        val normal = 300
        val slow = 500
        val emphasis = 800
    }

    /**
     * 状态颜色映射
     */
    @Composable
    @ReadOnlyComposable
    fun connectionStatusColor(connected: Boolean) = when {
        connected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    /**
     * 页面内边距（根据屏幕尺寸自适应）
     */
    fun pagePadding(isCompact: Boolean): PaddingValues = PaddingValues(
        horizontal = if (isCompact) Spacing.md else Spacing.lg,
        vertical = Spacing.md
    )

    /**
     * 消息列表内边距
     */
    fun messageListPadding(isCompact: Boolean): PaddingValues = PaddingValues(
        horizontal = if (isCompact) Spacing.md else Spacing.lg,
        vertical = Spacing.md
    )
}

/**
 * 本地提供的 Agent 主题
 */
val LocalAgentSpacing = staticCompositionLocalOf { AgentTheme.Spacing }
val LocalAgentShapes = staticCompositionLocalOf { AgentTheme.Shapes }
