package org.shiroumi.quant_kmp.ui.markdown.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.shiroumi.quant_kmp.ui.markdown.MarketSentimentBlockSpec

/**
 * 市场情绪自定义渲染块。
 *
 * 作为报告正文中的增强段落渲染，辩证关系使用 editorial callout 强调。
 */
@Composable
fun MarketSentimentBlock(
    spec: MarketSentimentBlockSpec,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "市场情绪",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = naturalizeReportText(spec.summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
        )

        Text(
            text = "市场情绪与个股情绪的辩证关系",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.tertiary
        )

        EditorialCalloutBlock(
            text = spec.dialecticalRelationship,
            accentColor = MaterialTheme.colorScheme.tertiary
        )
    }
}
