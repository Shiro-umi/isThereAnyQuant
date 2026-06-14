package org.shiroumi.quant_kmp.feature.sentiment.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.shiroumi.quant_kmp.ui.theme.quantColors

/**
 * 参数定义模型
 */
data class ParameterSpec(
    val id: String,
    val title: String,
    val unit: String,
    val yMin: Double,
    val yMax: Double,
    val color: @Composable () -> Color,
    val extractor: (model.candle.StrategySentimentResponse) -> Double,
    val formatter: (Double) -> String,
    val semantics: String,
    val tradingGuide: String,
    val thresholds: List<ThresholdLine> = emptyList(),
    val isFeatured: Boolean = false,
    val narrative: (Double) -> String = { "" },
)

data class ThresholdLine(
    val value: Double,
    val label: String,
    val color: @Composable () -> Color,
)

fun buildParameterSpecs(): List<ParameterSpec> = listOf(
    ParameterSpec(
        id = "sentiment_exposure",
        title = "市场情绪水位",
        unit = "%",
        yMin = 0.0,
        yMax = 1.0,
        color = { MaterialTheme.colorScheme.primary },
        extractor = { it.sentimentExposure },
        formatter = { "${formatDouble(it * 100, 1)}%" },
        semantics = "五维情绪因子融合后经三层保护机制调整的最终输出。用于解释市场环境，不再过滤 7% 盈利预测模型选股列表。",
        tradingGuide = "• >70%：多头强势\n• 40~70%：中性偏多\n• <40%：风险偏谨慎\n• =0%：绝对水位保护触发，但模型 Top5 仍会展示",
        thresholds = listOf(
            ThresholdLine(0.7, "70%") { MaterialTheme.colorScheme.primary },
            ThresholdLine(0.4, "40%") { MaterialTheme.colorScheme.secondary },
        ),
        isFeatured = true,
    ),
    ParameterSpec(
        id = "bull_ratio",
        title = "多头情绪广度",
        unit = "%",
        yMin = 0.0,
        yMax = 1.0,
        color = { MaterialTheme.colorScheme.secondary },
        extractor = { it.bullRatio },
        formatter = { "${formatDouble(it * 100, 1)}%" },
        semantics = "全市场500只样本股中，EMA10 > EMA30（短期趋势向上）的股票占比。是市场多头力量的最底层度量。",
        tradingGuide = "• >53%：多头行情\n• 46~53%：多空胶着\n• <46%：空头主导\n• <25.6%：触发绝对水位保护",
        narrative = {
            when {
                it >= 0.536 -> "市场整体多头情绪高涨，多数个股短期趋势向好。"
                it >= 0.46 -> "多空力量相对均衡，市场处于方向选择的关键期。"
                it >= 0.256 -> "空头情绪占优，赚钱效应减弱，需保持谨慎。"
                else -> "多头情绪极度低迷，已触发绝对水位保护。"
            }
        },
        thresholds = listOf(
            ThresholdLine(0.536, "53.6%") { MaterialTheme.colorScheme.primary },
            ThresholdLine(0.256, "25.6%") { MaterialTheme.quantColors.warning },
        ),
    ),
    ParameterSpec(
        id = "ratio_norm",
        title = "趋势健康度",
        unit = "%",
        yMin = 0.0,
        yMax = 1.0,
        color = { MaterialTheme.colorScheme.tertiary },
        extractor = { it.ratioNorm },
        formatter = { "${formatDouble(it * 100, 1)}%" },
        semantics = "将原始看涨比例非线性映射到标准安全范围，值过低时归零，超过健康阈值饱和为1。",
        tradingGuide = "• =100%：市场极度乐观\n• 50~80%：健康多头状态\n• <20%：极度悲观\n• =0%：市场全面弱势",
        narrative = {
            when {
                it >= 0.80 -> "市场趋势健康度极佳，多头环境稳固，可积极配置。"
                it >= 0.50 -> "趋势健康度良好，市场情绪处于相对安全的偏多状态。"
                it >= 0.20 -> "趋势健康度偏弱，市场情绪趋于谨慎，建议控制仓位。"
                else -> "趋势健康度极差，市场全面弱势，不宜轻易开仓。"
            }
        },
    ),
    ParameterSpec(
        id = "fft_score",
        title = "周期相位指标",
        unit = "%",
        yMin = 0.0,
        yMax = 1.0,
        color = { MaterialTheme.quantColors.warning },
        extractor = { it.fftScore },
        formatter = { "${formatDouble(it * 100, 1)}%" },
        semantics = "对看涨比例序列做滚动FFT变换，提取主频的增强相位信号。周期谷底=高分（即将上升），周期顶部=低分（即将下降）。",
        tradingGuide = "• >70%：周期底部回升段，偏多信号\n• 40~60%：中性区间\n• <30%：周期顶部回落段，偏空信号\n⚠️ 权重最小(6.6%)",
        narrative = {
            when {
                it >= 0.70 -> "当前处于周期底部回升段，市场情绪即将向上反转。"
                it >= 0.40 -> "位于周期中性区间，方向暂不明朗。"
                else -> "当前处于周期顶部回落段，情绪面临下行拐点风险。"
            }
        },
    ),
    ParameterSpec(
        id = "residual_score",
        title = "周期偏离度",
        unit = "%",
        yMin = 0.0,
        yMax = 1.0,
        color = { MaterialTheme.colorScheme.outline },
        extractor = { it.residualScore },
        formatter = { "${formatDouble(it * 100, 1)}%" },
        semantics = "利用回归模型拟合看涨比例的周期规律，计算当前实际值偏离模型预测的期望偏差。",
        tradingGuide = "• >70%：实际低于周期预期 → 超卖机会\n• 40~60%：符合周期预期\n• <30%：实际高于预期 → 过热风险\n📊 权重较高(24.1%)",
        narrative = {
            when {
                it >= 0.70 -> "实际值低于周期预期，市场可能存在超卖机会。"
                it >= 0.40 -> "当前走势与周期模型基本吻合。"
                else -> "实际值高于周期预期，需警惕过热回调风险。"
            }
        },
    ),
    ParameterSpec(
        id = "market_vol",
        title = "市场波动率",
        unit = "%",
        yMin = 0.0,
        yMax = 0.05,
        color = { MaterialTheme.colorScheme.tertiary },
        extractor = { it.marketVol },
        formatter = { "${formatDouble(it * 100, 2)}%" },
        semantics = "全市场500只样本股20日滚动日收益率标准差的均值，反映市场整体波动剧烈程度。",
        tradingGuide = "• <1.5%：市场平静\n• 1.5~2.5%：正常区间\n• 2.5~3.5%：波动加剧\n• >3.5%：极端波动（如股灾）",
        narrative = {
            when {
                it < 0.015 -> "市场整体波动温和，风险偏好环境相对友好。"
                it < 0.025 -> "波动率处于常态区间，市场暂未出现明显恐慌。"
                it < 0.035 -> "波动正在抬升，仓位管理需要同步收紧。"
                else -> "波动率显著放大，市场进入高风险震荡区。"
            }
        },
    ),
    ParameterSpec(
        id = "vol_z",
        title = "波动率偏离度",
        unit = "σ",
        yMin = -3.0,
        yMax = 5.0,
        color = { MaterialTheme.quantColors.warning },
        extractor = { it.volZ },
        formatter = { "${formatDouble(it, 2)}σ" },
        semantics = "当前波动率相对252日滚动均值的标准化偏离。驱动动态权重调节(>2σ)和VOL Cap上限(>2σ)。",
        tradingGuide = "• <0σ：低波动环境\n• 0~2σ：标准权重运作\n• >2σ：触发动态权重\n• >2.5σ：VOL Cap施加硬上限",
        narrative = {
            when {
                it < 0.0 -> "当前波动低于历史均值，市场风险压力有限。"
                it < 2.0 -> "波动偏离仍在可控范围内，系统保持常规权重。"
                it < 2.5 -> "波动已明显高于常态，动态抑制机制开始增强。"
                else -> "波动极端偏离历史均值，情绪上限保护接近强制收缩。"
            }
        },
        thresholds = listOf(
            ThresholdLine(2.0, "2σ 动态权重") { MaterialTheme.colorScheme.secondary },
            ThresholdLine(0.0, "0σ 均值") { MaterialTheme.colorScheme.outline },
        ),
    ),
    ParameterSpec(
        id = "vol_score",
        title = "恐慌抑制系数",
        unit = "%",
        yMin = 0.0,
        yMax = 1.0,
        color = { MaterialTheme.quantColors.warning },
        extractor = { it.volScore },
        formatter = { "${formatDouble(it * 100, 1)}%" },
        semantics = "将波动率Z值进行非线性翻转缩放。高波动→低分→显著强制降仓。",
        tradingGuide = "• >60%：低波动，不压制仓位\n• 40~60%：中性\n• <40%：高波动恐慌，显著压低仓位\n📊 权重较高(23.2%)",
        narrative = {
            when {
                it > 0.60 -> "恐慌抑制较弱，波动尚未对仓位形成明显压制。"
                it > 0.40 -> "系统开始感知波动风险，仓位会进入审慎区。"
                else -> "恐慌抑制显著增强，系统会主动压低风险暴露。"
            }
        },
    ),
    ParameterSpec(
        id = "accel_z",
        title = "情绪加速度",
        unit = "σ",
        yMin = -5.0,
        yMax = 5.0,
        color = { MaterialTheme.colorScheme.tertiary },
        extractor = { it.accelZ },
        formatter = { "${formatDouble(it, 2)}σ" },
        semantics = "看涨比例的一阶差分经EMA10平滑后，再做252日Z标准化。衡量市场情绪「变化的速度」。",
        tradingGuide = "• >1σ：情绪快速改善\n• 0~1σ：情绪温和改善\n• -1~0σ：情绪缓慢恶化\n• <-1σ：情绪急剧恶化",
        narrative = {
            when {
                it >= 1.0 -> "情绪改善速度非常快，短期 risk-on 力度明显增强。"
                it >= 0.0 -> "情绪仍在向上修复，但上行动能偏温和。"
                it >= -1.0 -> "情绪边际开始走弱，市场偏向谨慎震荡。"
                else -> "情绪恶化速度较快，短线回撤风险正在放大。"
            }
        },
        thresholds = listOf(
            ThresholdLine(1.0, "+1σ") { MaterialTheme.colorScheme.primary },
            ThresholdLine(0.0, "0σ") { MaterialTheme.colorScheme.outline },
            ThresholdLine(-1.0, "-1σ") { MaterialTheme.quantColors.warning },
        ),
    ),
    ParameterSpec(
        id = "accel_score",
        title = "情绪动能指标",
        unit = "%",
        yMin = 0.0,
        yMax = 1.0,
        color = { MaterialTheme.colorScheme.primary },
        extractor = { it.accelScore },
        formatter = { "${formatDouble(it * 100, 1)}%" },
        semantics = "将加速度Z值非线性映射放缩以捕捉异动，敏感度系数较高。",
        tradingGuide = "• >70%：趋势加速上升，积极做多\n• 40~60%：趋势平稳，中性\n• <30%：趋势加速下跌，降仓\n📊 权重(21.1%)",
        narrative = {
            when {
                it >= 0.70 -> "动能得分处于强势区间，情绪趋势具备持续上冲能力。"
                it >= 0.40 -> "动能仍偏正面，但更像稳步推进而非爆发式强化。"
                else -> "动能得分走弱，情绪修复难以转化成稳定仓位优势。"
            }
        },
    ),
    ParameterSpec(
        id = "absolute_floor",
        title = "情绪安全线",
        unit = "",
        yMin = 0.0,
        yMax = 1.0,
        color = { MaterialTheme.quantColors.success },
        extractor = { it.absoluteFloor },
        formatter = { "${formatDouble(it * 100, 1)}%" },
        semantics = "看涨比例是否满足情绪安全红线。该指标只解释市场环境，不再隐藏 7% 盈利预测模型选股。",
        tradingGuide = "• =1：情绪安全线通过\n• =0：情绪保护触发\n模型 Top5 仍会在策略选股列表展示",
        narrative = {
            when {
                it >= 0.90 -> "安全水位极高，市场情绪处于较稳定区间。"
                it >= 0.50 -> "安全水位通过，市场情绪未触发底线保护。"
                else -> "安全水位未通过，情绪保护触发，但模型选股列表继续展示。"
            }
        },
        thresholds = listOf(
            ThresholdLine(0.5, "开/关") { MaterialTheme.quantColors.warning },
        ),
    ),
)
