package org.shiroumi.quant_kmp.ui.markdown.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shiroumi.quant_kmp.ui.markdown.ReportHeaderSpec

/**
 * 杂志封面式报告头部
 *
 * 设计理念：
 * - 标题优先：大字体标题在第一行
 * - 信息主导：股票名称和标题是焦点
 * - 元信息低调：分析类型、股票代码、交易日期在底部
 * - 极简克制：去除所有装饰性元素
 */
@Composable
fun ReportHeaderBlock(
    spec: ReportHeaderSpec,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 主标题区域 - 第一行
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 股票名称（如果有）
            spec.stockName?.let {
                Text(
                    text = it,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 40.sp,
                    letterSpacing = (-0.5).sp
                )
            }

            // 报告标题
            Text(
                text = spec.title,
                fontSize = if (spec.stockName != null) 20.sp else 32.sp,
                fontWeight = if (spec.stockName != null) FontWeight.Normal else FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (spec.stockName != null) 0.7f else 1f
                ),
                lineHeight = if (spec.stockName != null) 28.sp else 40.sp,
                letterSpacing = 0.sp
            )
        }

        // 元信息行 - 底部，低调
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 分析类型
            Text(
                text = spec.analysisType,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )

            // 股票代码
            Text(
                text = spec.stockCode,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 交易日（如果有）
            spec.tradeDate?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
