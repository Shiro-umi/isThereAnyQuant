package org.shiroumi.quant_kmp.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 居中内容布局
 * 在大屏幕上限制最大宽度并居中显示，小屏幕上填满宽度
 *
 * @param maxWidth 最大宽度，默认 800dp
 * @param horizontalPadding 水平内边距，默认 16dp
 * @param verticalPadding 垂直内边距，默认 24dp
 * @param content 内容
 */
@Composable
fun CenteredContainer(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 800.dp,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 24.dp,
    contentAlignment: Alignment = Alignment.TopCenter,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = contentAlignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxHeight()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            content = content
        )
    }
}

/**
 * 居中列布局
 * 适用于表单、详情页等垂直排列的内容
 *
 * @param maxWidth 最大宽度，默认 640.dp
 * @param horizontalAlignment 水平对齐方式
 * @param verticalArrangement 垂直排列方式
 * @param content 内容
 */
@Composable
fun CenteredColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 640.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    verticalArrangement: androidx.compose.foundation.layout.Arrangement.Vertical = androidx.compose.foundation.layout.Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = maxWidth)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

/**
 * 页面内容容器
 * 用于包裹页面主要内容，提供统一的宽度和内边距
 *
 * @param maxWidth 最大宽度，默认 900.dp
 * @param content 内容
 */
@Composable
fun PageContentContainer(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 900.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxHeight()
                .padding(24.dp),
            content = content
        )
    }
}
