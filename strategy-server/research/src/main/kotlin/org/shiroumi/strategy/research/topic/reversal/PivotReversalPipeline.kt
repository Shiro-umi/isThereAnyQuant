package org.shiroumi.strategy.research.topic.reversal

import org.shiroumi.strategy.research.pipeline.ResearchContext
import java.nio.file.Path

/**
 * 趋势反转 topic 的研究管线入口 —— **占位骨架，尚未编码**。
 *
 * 本 topic 目前只有调研文档 `temp/pivot-reversal-formula.html`，尚未进入实现期。
 * 它与 trend（趋势跟踪）平级，同样**依赖 factor 共享层**：
 * - 消费 factor 的 OHLC 日内路径因子作为反转算子原料；
 * - 以**只读方式**消费 trend 的趋势 score 作为「背离基准」（复用而不侵犯，见调研文档 §III）；
 * - 产物为「当天顶部反转概率 P^rev」与对应裁判，沿用或扩展 factor 的通用度量契约。
 *
 * 研究目标三层（判别 / 报警 / 时效）与 loss 设计（类别加权 Focal BCE + 排序代理）详见调研文档 §V–§VII。
 *
 * TODO(reversal): 立项后实现 PivotStudy（反转算子族 + P^rev）/ PivotEvaluation（三层指标裁判）/ 落盘，
 * 并复用 tuner（可微分支最小化 L_total，含硬门控时切黑盒分支优化 -AUC）。
 */
object PivotReversalPipeline {

    fun run(ctx: ResearchContext): List<Path> {
        TODO("reversal 趋势反转管线待实现：参见 temp/pivot-reversal-formula.html")
    }
}
