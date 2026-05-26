package org.shiroumi.quant_kmp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 聊天气泡原子组件
 *
 * 职责单一：负责气泡的背景色、圆角形状、内边距与最大宽度约束。
 * 内容完全由调用方通过 [content] 插槽注入，不持有任何业务逻辑。
 *
 * @param containerColor   气泡背景色
 * @param isUserMessage    true → 右下角尖角（用户），false → 左下角尖角（Agent）
 * @param maxWidth         气泡最大宽度，默认 520.dp
 * @param cornerRadius     主圆角半径，默认 18.dp
 * @param cornerRadiusSmall 尖角侧圆角半径，默认 12.dp
 * @param contentPadding   气泡内边距，默认 12.dp
 * @param modifier         外部 Modifier
 * @param content          气泡内容插槽
 */
@Composable
fun BaseChatBubble(
    containerColor: Color,
    isUserMessage: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 520.dp,
    cornerRadius: Dp = 18.dp,
    cornerRadiusSmall: Dp = 12.dp,
    contentPadding: Dp = 12.dp,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(
        topStart = cornerRadius,
        topEnd = cornerRadius,
        bottomStart = if (isUserMessage) cornerRadius else cornerRadiusSmall,
        bottomEnd = if (isUserMessage) cornerRadiusSmall else cornerRadius
    )

    Surface(
        color = containerColor,
        shape = shape,
        modifier = modifier.widthIn(max = maxWidth)
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
