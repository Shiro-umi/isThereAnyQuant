package org.shiroumi.strategy.service.postmarket

import kotlinx.datetime.LocalDate
import org.shiroumi.strategy.core.daily.TargetPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [PostMarketPreparationJob.mergePriorLimitPrices] 纯逻辑测试——验证全链重算前「保留上一轮已落库买点」的
 * 合并语义。覆盖 agent 买点回填跨重算幂等的关键防回归点：重算产出 limitPrice 恒为 null，必须从旧快照回填，
 * 否则每次重算（数据更新追平/补偿重跑）都把买点抹回 null 触发全量重跑 agent。
 */
class PostMarketPreparationJobMergeLimitTest {

    private val tradeDate = LocalDate(2026, 6, 22)
    private val targetDate = LocalDate(2026, 6, 23)

    private fun target(tsCode: String, score: Double, limit: Double? = null) = TargetPosition(
        tradeDate = tradeDate,
        targetDate = targetDate,
        tsCode = tsCode,
        selectionScore = score,
        selected = true,
        targetWeight = 0.2,
        sentimentExposure = 0.0,
        selectionReason = "profit-prediction-7pct:test",
        limitPrice = limit,
    )

    @Test
    fun `空快照原样返回同一引用`() {
        val targets = listOf(target("603778.SH", 0.99), target("301310.SZ", 0.98))
        val merged = PostMarketPreparationJob.mergePriorLimitPrices(targets, emptyMap())
        assertSame(targets, merged, "空旧买点快照应短路返回原列表，零拷贝")
    }

    @Test
    fun `null买点的票从旧快照回填`() {
        val targets = listOf(target("603778.SH", 0.99), target("301310.SZ", 0.98))
        val prior = mapOf("603778.SH" to 14.08, "301310.SZ" to 42.80)
        val merged = PostMarketPreparationJob.mergePriorLimitPrices(targets, prior)
        assertEquals(14.08, merged.first { it.tsCode == "603778.SH" }.limitPrice)
        assertEquals(42.80, merged.first { it.tsCode == "301310.SZ" }.limitPrice)
    }

    @Test
    fun `旧快照缺该票时保持null不入场口径变开盘价`() {
        val targets = listOf(target("603778.SH", 0.99), target("002167.SZ", 0.97))
        // 旧快照只回填了 603778，002167 上一轮 agent 失败无买点。
        val prior = mapOf("603778.SH" to 14.08)
        val merged = PostMarketPreparationJob.mergePriorLimitPrices(targets, prior)
        assertEquals(14.08, merged.first { it.tsCode == "603778.SH" }.limitPrice)
        assertNull(merged.first { it.tsCode == "002167.SZ" }.limitPrice, "旧快照无此票时保持 null，由 backfill 重补")
    }

    @Test
    fun `已有买点的票不被旧值覆盖`() {
        // 防御分支：重算产出理论恒为 null，但若带值则以重算值为准，不被旧快照覆盖。
        val targets = listOf(target("603778.SH", 0.99, limit = 15.00))
        val prior = mapOf("603778.SH" to 14.08)
        val merged = PostMarketPreparationJob.mergePriorLimitPrices(targets, prior)
        assertEquals(15.00, merged.first { it.tsCode == "603778.SH" }.limitPrice, "本轮已有买点优先，不被旧快照覆盖")
    }
}
