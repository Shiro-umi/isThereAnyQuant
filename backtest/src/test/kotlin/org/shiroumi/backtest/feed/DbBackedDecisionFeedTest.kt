package org.shiroumi.backtest.feed

import org.shiroumi.backtest.domain.Side
import org.shiroumi.backtest.domain.StrategyDecision
import org.shiroumi.backtest.testing.T0
import org.shiroumi.backtest.testing.T1
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class DbBackedDecisionFeedTest {

    @Test fun `daily_target_portfolio 转成目标组合且只保留选中权重`() {
        val feed = DbBackedDecisionFeed(
            dataSource = FakeDecisionDataSource(
                targets = listOf(
                    target("000001.SZ", selected = true, weight = 0.2),
                    target("000002.SZ", selected = false, weight = 0.0),
                ),
            ),
        )

        val decision = feed.decisionsFor(T1).single() as StrategyDecision.TargetPortfolioDecision

        assertEquals(mapOf("000001.SZ" to 0.2), decision.targetWeights)
        assertEquals(0.5, decision.sentimentExposure)
    }

    @Test fun `目标组合存在时不叠加 audit 信号以避免重复意图`() {
        val feed = DbBackedDecisionFeed(
            dataSource = FakeDecisionDataSource(
                targets = listOf(target("000001.SZ", selected = true, weight = 0.2)),
                signals = listOf(signal("000001.SZ", "BUY")),
            ),
        )

        assertTrue(feed.decisionsFor(T1).single() is StrategyDecision.TargetPortfolioDecision)
    }

    @Test fun `目标组合缺失时可从 audit 新增和剔除列表生成显式意图`() {
        val feed = DbBackedDecisionFeed(
            dataSource = FakeDecisionDataSource(
                signals = listOf(signal("000001.SZ", "BUY"), signal("000002.SZ", "SELL")),
            ),
            auditSourceDateResolver = { T1 },
        )

        val decisions = feed.decisionsFor(T1).filterIsInstance<StrategyDecision.TradeIntentDecision>()

        assertEquals(listOf(Side.BUY, Side.SELL), decisions.map { it.side })
        assertEquals(listOf("000001.SZ", "000002.SZ"), decisions.map { it.tsCode })
    }

    @Test fun `目标日缺失组合时使用上一交易日 audit 作为降级信号源`() {
        val feed = DbBackedDecisionFeed(
            dataSource = FakeDecisionDataSource(
                signals = listOf(signal("000001.SZ", "BUY", tradeDate = T0)),
            ),
            auditSourceDateResolver = { T0 },
        )

        val decision = feed.decisionsFor(T1).single() as StrategyDecision.TradeIntentDecision

        assertEquals(T1, decision.effectiveDate)
        assertEquals("000001.SZ", decision.tsCode)
        assertEquals(Side.BUY, decision.side)
    }

    @Test fun `适配器丢弃脏数据里的账户字段并保持 StrategyDecision 字段干净`() {
        val feed = DbBackedDecisionFeed(
            dataSource = FakeDecisionDataSource(
                targets = listOf(
                    target(
                        tsCode = "000001.SZ",
                        selected = true,
                        weight = 0.2,
                        metadata = mapOf(
                            "quantity" to "1000",
                            "availableCash" to "999999",
                            "selectionScore" to "0.88",
                        ),
                    )
                ),
            ),
        )

        val decision = feed.decisionsFor(T1).single()

        assertFalse(decision.reason.contains("quantity"))
        assertFalse(decision.reason.contains("availableCash"))
        assertTrue(decision.reason.contains("selectionScore"))
        assertDecisionFieldsClean(decision)
    }

    private fun target(
        tsCode: String,
        selected: Boolean,
        weight: Double,
        metadata: Map<String, String> = emptyMap(),
    ): TargetPortfolioRow = TargetPortfolioRow(
        tradeDate = T0,
        targetDate = T1,
        tsCode = tsCode,
        selected = selected,
        targetWeight = weight,
        sentimentExposure = 0.5,
        selectionReason = "test",
        metadata = metadata,
    )

    private fun signal(tsCode: String, side: String, tradeDate: kotlinx.datetime.LocalDate = T1): AuditSignalRow = AuditSignalRow(
        tradeDate = tradeDate,
        tsCode = tsCode,
        side = side,
        reason = "audit",
    )

    private fun assertDecisionFieldsClean(decision: StrategyDecision) {
        val forbidden = listOf("quantity", "cash", "position", "availableqty", "availablecash", "currentquantity")
        val violations = decision::class.memberProperties.map { it.name.lowercase() }
            .filter { name -> forbidden.any { it in name } }
        if (violations.isNotEmpty()) fail("StrategyDecision 含账户字段: $violations")
    }
}

private class FakeDecisionDataSource(
    private val targets: List<TargetPortfolioRow> = emptyList(),
    private val signals: List<AuditSignalRow> = emptyList(),
) : StrategyDecisionDataSource {
    override fun targetRowsFor(date: kotlinx.datetime.LocalDate): List<TargetPortfolioRow> =
        targets.filter { it.targetDate == date }

    override fun auditSignalsFor(date: kotlinx.datetime.LocalDate): List<AuditSignalRow> =
        signals.filter { it.tradeDate == date }
}
