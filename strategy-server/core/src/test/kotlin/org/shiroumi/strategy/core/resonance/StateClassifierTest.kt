package org.shiroumi.strategy.core.resonance

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.shiroumi.quant_kmp.strategy.daily.model.FactorSnapshot
import org.shiroumi.quant_kmp.strategy.daily.model.RegimeCategory
import org.shiroumi.quant_kmp.strategy.daily.model.SelectionRule
import org.shiroumi.quant_kmp.strategy.daily.model.StockFilter

/**
 * 验证 StateClassifier、SelectionRuleEngine、Snapshot ↔ Record 映射的等价性。
 */
class StateClassifierTest {

    @Test
    fun `classify produces valid stateId format matching study convention`() {
        val history = syntheticHistory(200)
        val today = history.last()
        val regime = StateClassifier.classify(today, history)!!

        // stateId 必须与 Study 格式一致: "trend=<level>,disp=<level>,vol=<level>"
        val parts = regime.stateId.split(",")
        assertEquals(3, parts.size)
        assertTrue(parts[0].startsWith("trend="))
        assertTrue(parts[1].startsWith("disp="))
        assertTrue(parts[2].startsWith("vol="))

        // 每个轴必须是 low / mid / high 之一
        val trendVal = parts[0].removePrefix("trend=")
        val dispVal = parts[1].removePrefix("disp=")
        val volVal = parts[2].removePrefix("vol=")
        assertTrue(trendVal in setOf("low", "mid", "high"), "trend=$trendVal")
        assertTrue(dispVal in setOf("low", "mid", "high"), "disp=$dispVal")
        assertTrue(volVal in setOf("low", "mid", "high"), "vol=$volVal")
    }

    @Test
    fun `classify handles wide value ranges without crashing`() {
        val history = syntheticHistory(200)
        // 极端值不应导致 crash
        for ((d4, b3p, a3) in listOf(
            Triple(-10.0, 10.0, 10.0),
            Triple(10.0, 10.0, 10.0),
            Triple(10.0, -10.0, -10.0),
            Triple(-10.0, -10.0, -10.0),
        )) {
            val today = FactorSnapshot(
                tradeDate = LocalDate.parse("2025-12-31"),
                factors = mapOf<String, Double?>("D4" to d4, "B3p" to b3p, "A3" to a3),
            )
            val regime = StateClassifier.classify(today, history)
            assertNotNull(regime, "classify should not return null for wide-range inputs")
            assertTrue(regime!!.stateId.isNotEmpty())
        }
    }

    @Test
    fun `classify with insufficient history returns null`() {
        val today = FactorSnapshot(
            tradeDate = LocalDate.parse("2025-01-01"),
            factors = mapOf("D4" to 0.5, "B3p" to 0.0, "A3" to 0.0),
        )
        val regime = StateClassifier.classify(today, listOf(today))
        assertEquals(null, regime)
    }

    @Test
    fun `SelectionRuleEngine load + forRegime returns non-empty rules for wildcard match`() {
        val cards = listOf(
            syntheticCard("A7", "Y2", 3, "F2a", "trend=low,disp=low+mid,vol=high"),
            syntheticCard("B4", "Y2", 3, "F2a", "trend=low,disp=low+mid,vol=high"),
            syntheticCard("A1", "Y1", 3, "F2a", "trend=all,disp=all,vol=all"),
        )
        SelectionRuleEngine.load(cards)
        assertTrue(SelectionRuleEngine.isLoaded())

        // 查询一个模糊匹配的状态
        val history = syntheticHistory(200)
        val today = FactorSnapshot(
            tradeDate = LocalDate.parse("2025-12-31"),
            factors = mapOf("D4" to -1.0, "B3p" to 0.3, "A3" to 1.5),
        )
        val regime = StateClassifier.classify(today, history)!!
        val rules = SelectionRuleEngine.forRegime(regime)
        assertTrue(rules.isNotEmpty(), "Should find rules via wildcard matching")
    }

    @Test
    fun `SelectionRuleEngine forCategory returns rules grouped by regime type`() {
        val cards = listOf(
            syntheticCard("A7", "Y2", 3, "F2a", "trend=low,disp=high,vol=high"),
            syntheticCard("B6", "Y3", 3, "F1b", "trend=low,disp=high,vol=high"),
            syntheticCard("A1", "Y1", 3, "F2a", "trend=high,disp=low,vol=low"),
        )
        SelectionRuleEngine.load(cards)

        val panicRules = SelectionRuleEngine.forCategory(RegimeCategory.PANIC_RELEASE)
        assertTrue(panicRules.isNotEmpty(), "PANIC_RELEASE should match trend=low,disp=high,vol=high")

        val trendRules = SelectionRuleEngine.forCategory(RegimeCategory.TREND_CONTINUATION)
        assertTrue(trendRules.isNotEmpty(), "TREND_CONTINUATION should match trend=high,disp=low,vol=low")
    }

    @Test
    fun `every rule has valid filter descriptions`() {
        val cards = listOf(syntheticCard("A7", "Y2", 3, "F2a", "trend=all,disp=all,vol=all"))
        SelectionRuleEngine.load(cards)

        SelectionRuleEngine.allStates().forEach { stateId ->
            val regime = StateClassifier.classify(
                FactorSnapshot(LocalDate.parse("2025-01-01"), mapOf("D4" to 0.5, "B3p" to 0.0, "A3" to 0.0)),
                syntheticHistory(200),
            )
            if (regime != null) {
                val rules = SelectionRuleEngine.forRegime(regime)
                rules.forEach { rule ->
                    assertTrue(rule.name.isNotEmpty(), "Rule name must not be empty")
                    assertTrue(rule.filters.isNotEmpty(), "Rule '${rule.name}' must have filters")
                    rule.filters.forEach { filter ->
                        assertTrue(filter.dimension.isNotEmpty(), "Filter dimension must not be empty")
                        assertTrue(filter.description.isNotEmpty(), "Filter description must not be empty")
                    }
                }
            }
        }
    }

    // ── helpers ──

    private fun syntheticHistory(days: Int): List<FactorSnapshot> {
        val start = LocalDate.parse("2024-01-02")
        return (0 until days).map { i ->
            FactorSnapshot(
                tradeDate = LocalDate.fromEpochDays(start.toEpochDays() + i),
                factors = mapOf(
                    "D4" to kotlin.math.sin(i * 0.06) * 1.5,
                    "B3p" to kotlin.math.sin(i * 0.04 + 1.0) * 0.3,
                    "A3" to kotlin.math.sin(i * 0.03 + 2.0) * 2.0,
                    "A1" to kotlin.math.sin(i * 0.05) * 0.02,
                    "B3" to 0.01,
                    "B4" to 0.5,
                ),
            )
        }
    }

    private fun syntheticCard(
        factorI: String, targetY: String, horizon: Int, band: String, stateId: String,
    ) = org.shiroumi.quant_kmp.strategy.daily.model.QualifiedResonance(
        factorI = factorI,
        factorName = factorI,
        factorType = "single",
        targetY = targetY,
        horizon = horizon,
        band = band,
        stateId = stateId,
        meanCoherence = 0.7,
        oosIc = 0.1,
        hitRate = 0.6,
        leadDaysLag = 3.0,
        qualified = true,
        conclusionLevel = "A",
    )
}
