package org.shiroumi.quant_kmp.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.shiroumi.quant_kmp.ui.theme.appFontFamily

/**
 * 分析报告专用 Markdown 渲染组件。
 *
 * 在标准 [MarkdownText] 基础上增加 `quant-*` fenced code block 解析能力，
 * 将 Agent 输出的自定义组件声明渲染为真实业务组件（如 K 线图、指标卡）。
 *
 * 未识别的 `quant-*` 块降级为代码块。
 */
@Composable
fun AgentReportMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    val contentColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> LocalContentColor.current
    }
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

    val components = markdownComponents(
        codeFence = { model ->
            val node = model.node
            val content = model.content
            val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
                ?.getTextInNode(content)
                ?.toString()

            if (language != null && language.startsWith("quant-")) {
                // 从 AST 中提取代码块内容（同 MarkdownCodeFence 的提取逻辑）
                val codeBody = if (node.children.size >= 3) {
                    val langNode = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
                    val start = node.children[2].startOffset
                    val minCount = if (langNode != null && node.children.size > 3) 3 else 2
                    val end = node.children[(node.children.size - 2).coerceAtLeast(minCount)].endOffset
                    content.subSequence(start, end).toString().replaceIndent()
                } else ""

                val block = QuantMarkdownBlockParser.parse(language, codeBody)
                QuantMarkdownBlockRenderer(block = block)
            } else {
                MarkdownCodeFence(content, node, style = typography.code)
            }
        },
        table = { model ->
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
