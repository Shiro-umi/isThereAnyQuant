package org.shiroumi.quant_kmp.feature.agent.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import model.agent.AgentAnalysisResultDto
import model.agent.AgentAnalysisType
import model.agent.ShareStatsDto
import org.shiroumi.quant_kmp.ui.markdown.AgentReportMarkdownText

/**
 * 分析结果详情面板
 *
 * M3 设计规范：
 * - 无顶部 title，无分割线
 * - 元信息用 Chip + label 组合，视觉层次清晰
 * - Markdown 内容限宽居中，阅读体验舒适
 * - 分享按钮位于右上，已分享时旁边展示访问统计 chip
 *
 * @param result 当前展示的分析结果，null 时显示空状态
 * @param shareStats 分享统计；为 null 表示尚未加载完成
 * @param isSharing 正在生成/复制链接
 * @param onShareClick 点击分享按钮回调；只有 result 非空时回调有效
 * @param modifier 外部传入的 Modifier
 */
@Composable
fun AgentAnalysisDetailPanel(
    result: AgentAnalysisResultDto?,
    scrollState: ScrollState = rememberScrollState(),
    shareStats: ShareStatsDto? = null,
    isSharing: Boolean = false,
    onShareClick: (AgentAnalysisResultDto) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (result == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "请选择一条分析记录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 800.dp)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ShareToolbar(
                        shareStats = shareStats,
                        isSharing = isSharing,
                        onShareClick = { onShareClick(result) },
                    )

                    // Markdown 内容（含自定义 quant-header、K 线图等组件）
                    AgentReportMarkdownText(
                        text = result.contentMd,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ShareToolbar(
    shareStats: ShareStatsDto?,
    isSharing: Boolean,
    onShareClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        if (shareStats?.shareToken != null && shareStats.viewCount > 0) {
            AssistChip(
                onClick = onShareClick,
                label = {
                    Text(
                        text = "已被查看 ${shareStats.viewCount} 次",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            Spacer(modifier = Modifier.padding(start = 8.dp))
        }
        IconButton(onClick = onShareClick, enabled = !isSharing) {
            if (isSharing) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(2.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "分享",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun analysisTypeDetailColors(type: AgentAnalysisType): Pair<Color, Color> {
    return when (type) {
        AgentAnalysisType.ENTRY_EXIT ->
            MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)

        AgentAnalysisType.RESEARCH_REPORT ->
            MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)

        AgentAnalysisType.TREND ->
            MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)

        AgentAnalysisType.PATTERN ->
            MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)

        AgentAnalysisType.INSTITUTIONAL ->
            MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)

        AgentAnalysisType.SUPPORT_RESISTANCE ->
            MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)

        AgentAnalysisType.TECHNICAL_INDICATORS ->
            MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)

        AgentAnalysisType.VOLUME_PRICE ->
            MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)

        AgentAnalysisType.PRICE_ACTION ->
            MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)

        AgentAnalysisType.RISK_ASSESSMENT ->
            MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)

        AgentAnalysisType.MARKET_SENTIMENT ->
            MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)

        AgentAnalysisType.GENERAL ->
            MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
    }
}
