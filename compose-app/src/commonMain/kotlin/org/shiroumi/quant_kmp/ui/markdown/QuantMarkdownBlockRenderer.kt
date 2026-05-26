package org.shiroumi.quant_kmp.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.shiroumi.quant_kmp.ui.markdown.components.EmbeddedKLineChartBlock
import org.shiroumi.quant_kmp.ui.markdown.components.LimitUpAnalysisBlock
import org.shiroumi.quant_kmp.ui.markdown.components.MarketSentimentBlock
import org.shiroumi.quant_kmp.ui.markdown.components.ReportHeaderBlock
import org.shiroumi.quant_kmp.ui.markdown.components.VolumePriceAbnormalBlock

/**
 * 根据解析后的 [QuantBlock] 渲染对应的自定义组件。
 *
 * 本组件是转发枢纽，不直接持有关联 UI 状态。
 */
@Composable
fun QuantMarkdownBlockRenderer(
    block: QuantBlock,
    modifier: Modifier = Modifier
) {
    when (block) {
        is QuantBlock.Header -> ReportHeaderBlock(
            spec = block.spec,
            modifier = modifier
        )
        is QuantBlock.KLine -> EmbeddedKLineChartBlock(
            spec = block.spec,
            modifier = modifier
        )
        is QuantBlock.LimitUp -> LimitUpAnalysisBlock(
            spec = block.spec,
            modifier = modifier
        )
        is QuantBlock.VolumePrice -> VolumePriceAbnormalBlock(
            spec = block.spec,
            modifier = modifier
        )
        is QuantBlock.MarketSentiment -> MarketSentimentBlock(
            spec = block.spec,
            modifier = modifier
        )
        is QuantBlock.Unknown -> {
            // 未识别块不做特殊渲染，降级由外层 codeFence 处理
        }
    }
}
