package org.shiroumi.quant_kmp.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import org.shiroumi.quant_kmp.ui.theme.appFontFamily

/**
 * Markdown 文本渲染组件
 * 
 * 特性：
 * 1. 使用应用字体家族（中英文+Emoji 统一）
 * 2. 表格支持水平滚动，列宽自适应，并填满可用宽度
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    // 聚合颜色逻辑
    val contentColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> LocalContentColor.current
    }
    // 使用应用字体家族确保 Emoji 正确显示
    val fontFamily = appFontFamily()

    val typography = markdownTypography(
        h1 = MaterialTheme.typography.headlineLarge.copy(fontFamily = fontFamily, color = contentColor),
        h2 = MaterialTheme.typography.headlineMedium.copy(fontFamily = fontFamily, color = contentColor),
        h3 = MaterialTheme.typography.headlineSmall.copy(fontFamily = fontFamily, color = contentColor),
        h4 = MaterialTheme.typography.titleLarge.copy(fontFamily = fontFamily, color = contentColor),
        h5 = MaterialTheme.typography.titleMedium.copy(fontFamily = fontFamily, color = contentColor),
        h6 = MaterialTheme.typography.titleSmall.copy(fontFamily = fontFamily, color = contentColor),
        paragraph = style.copy(fontFamily = fontFamily, color = contentColor),
        ordered = MaterialTheme.typography.bodyMedium.copy(fontFamily = fontFamily, color = contentColor),
        bullet = MaterialTheme.typography.bodyMedium.copy(fontFamily = fontFamily, color = contentColor),
        list = MaterialTheme.typography.bodyMedium.copy(fontFamily = fontFamily, color = contentColor),
        code = MaterialTheme.typography.bodyMedium.copy(fontFamily = fontFamily, color = contentColor),
        quote = MaterialTheme.typography.bodyMedium.copy(fontFamily = fontFamily, color = contentColor),
        inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = fontFamily, color = contentColor),
        table = MaterialTheme.typography.bodyMedium.copy(fontFamily = fontFamily, color = contentColor)
    )

    val colors = markdownColor(
        text = contentColor,
        codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        dividerColor = MaterialTheme.colorScheme.outlineVariant
    )

    val padding = markdownPadding(
        block = 8.dp,
        list = 4.dp,
        indentList = 8.dp
    )

    // 自定义表格组件：填满宽度 + 水平滚动
    val components = markdownComponents(
        table = { model ->
            // 内层支持水平滚动
            Box(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                MarkdownTable(
                    content = model.content,
                    node = model.node,
                    style = typography.table,
                    rowBlock = { content, header, tableWidth, style ->
                        MarkdownTableRow(
                            content = content,
                            header = header,
                            tableWidth = tableWidth,
                            style = style,
                            annotatorSettings = annotatorSettings(),
                            maxLines = 5,
                        )
                    }
                )
            }
        }
    )

    Markdown(
        content = text,
        typography = typography,
        colors = colors,
        padding = padding,
        components = components,
        dimens = markdownDimens(
            tableCellWidth = 240.dp
        ),
        modifier = modifier.fillMaxWidth()
    )
}
