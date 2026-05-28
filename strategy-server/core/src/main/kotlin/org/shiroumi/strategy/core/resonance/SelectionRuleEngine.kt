package org.shiroumi.strategy.core.resonance

import org.shiroumi.quant_kmp.strategy.daily.model.MarketRegime
import org.shiroumi.quant_kmp.strategy.daily.model.QualifiedResonance
import org.shiroumi.quant_kmp.strategy.daily.model.RegimeCategory
import org.shiroumi.quant_kmp.strategy.daily.model.SelectionRule
import org.shiroumi.quant_kmp.strategy.daily.model.StockFilter

/**
 * 选股规则引擎 —— 从 qualified 共振卡片到可执行选股规则。
 *
 * 加载 qualified 共振卡片，建立 stateId → rules 的索引。
 * 应用启动时调用一次 [load]，之后所有查询为 O(1) HashMap 查找。
 */
object SelectionRuleEngine {

    private val ruleIndex = linkedMapOf<String, List<SelectionRule>>()
    private var loaded = false

    /** 加载 qualified 共振卡片并建立索引。在应用启动时或研究迭代后调用。 */
    fun load(cards: List<QualifiedResonance>) {
        ruleIndex.clear()
        val qualified = cards.filter { it.qualified }

        val byState = qualified.groupBy { it.stateId }
        for ((stateId, stateCards) in byState) {
            val rules = buildRulesForState(stateId, stateCards)
            ruleIndex[stateId] = rules
        }

        val globalCards = qualified.filter { it.stateId == "trend=all,disp=all,vol=all" }
        if (globalCards.isNotEmpty()) {
            ruleIndex["__global__"] = buildGlobalRules(globalCards)
        }
        loaded = true
    }

    fun isLoaded(): Boolean = loaded

    /** 精确匹配 stateId，然后模糊匹配（合并状态通配），最后 fallback 到全局基线。 */
    fun forRegime(regime: MarketRegime): List<SelectionRule> {
        ruleIndex[regime.stateId]?.let { return it }

        val wildcardPatterns = buildWildcardPatterns(regime)
        for (pattern in wildcardPatterns) {
            for ((stateId, rules) in ruleIndex) {
                if (stateId == "__global__") continue
                if (matchesWildcard(stateId, pattern)) return rules
            }
        }
        return ruleIndex["__global__"] ?: emptyList()
    }

    /** 按 RegimeCategory 粗粒度查询 */
    fun forCategory(category: RegimeCategory): List<SelectionRule> {
        val matching = mutableListOf<SelectionRule>()
        for ((stateId, rules) in ruleIndex) {
            if (stateId == "__global__") continue
            if (matchesCategory(category, stateId)) matching.addAll(rules)
        }
        return matching.distinctBy { it.name }.sortedByDescending { it.evidenceCardCount }
    }

    fun allStates(): List<String> = ruleIndex.keys.filter { it != "__global__" }.toList()

    // ── 内部方法 ──

    private fun buildRulesForState(stateId: String, cards: List<QualifiedResonance>): List<SelectionRule> {
        val rulesByFactor = linkedMapOf<String, MutableList<QualifiedResonance>>()
        for (card in cards) rulesByFactor.getOrPut(card.factorI) { mutableListOf() }.add(card)

        return rulesByFactor.mapNotNull { (factor, factorCards) ->
            factorRuleMap[factor]?.copy(
                regimeStateId = stateId,
                evidenceCardCount = factorCards.size,
                targetY = factorCards.groupBy { it.targetY }.maxBy { it.value.size }.key,
                horizon = factorCards.groupBy { it.horizon }.maxBy { it.value.size }.key,
                dominantBand = factorCards.groupBy { it.band }.maxBy { it.value.size }.key,
                positionRatio = positionRatio(factorCards.mapNotNull { it.meanCoherence }.average().let { if (it.isNaN()) 0.0 else it }),
                estimatedPoolSize = poolSize(factor),
            )
        }.sortedByDescending { it.evidenceCardCount }
    }

    private fun buildGlobalRules(cards: List<QualifiedResonance>): List<SelectionRule> =
        buildRulesForState("trend=all,disp=all,vol=all", cards)

    private fun buildWildcardPatterns(regime: MarketRegime): List<String> {
        val results = mutableListOf<String>()
        for (t in expand(regime.trendLevel.name.lowercase()))
            for (d in expand(regime.dispersionLevel.name.lowercase()))
                for (v in expand(regime.volumeLevel.name.lowercase()))
                    results.add("trend=$t,disp=$d,vol=$v")
        return results
    }

    private fun expand(l: String): List<String> = when (l) {
        "low"  -> listOf("low", "low+mid")
        "mid"  -> listOf("mid", "low+mid", "mid+high", "low+mid+high")
        "high" -> listOf("high", "mid+high")
        else   -> listOf(l)
    }

    private fun matchesWildcard(cardStateId: String, pattern: String): Boolean {
        val cardParts = cardStateId.split(",").associate { val (k, v) = it.split("=", limit = 2); k to v }
        val patternParts = pattern.split(",").associate { val (k, v) = it.split("=", limit = 2); k to v }
        for ((key, pv) in patternParts) {
            val cv = cardParts[key] ?: return false
            if (!cv.split("+").contains(pv)) return false
        }
        return true
    }

    private fun matchesCategory(category: RegimeCategory, stateId: String): Boolean {
        val p = stateId.split(",").associate { val (k, v) = it.split("=", limit = 2); k to v }
        val trend = p["trend"] ?: return false
        val disp = p["disp"] ?: return false
        val vol = p["vol"] ?: return false
        val hiDisp = disp.contains("high"); val hiVol = vol.contains("high")
        val loDisp = disp == "low"; val loVol = vol == "low"
        val trendLo = trend.contains("low") && !trend.contains("high")
        val trendHi = trend.contains("high") && !trend.contains("low")
        return when (category) {
            RegimeCategory.PANIC_RELEASE -> trendLo && hiDisp && hiVol
            RegimeCategory.STRONG_OSCILLATION -> trendHi && hiDisp && hiVol
            RegimeCategory.TREND_CONTINUATION -> trendHi && loDisp && loVol
            RegimeCategory.BOTTOM_WATCH -> trendLo && loDisp && loVol
            RegimeCategory.EXTREME_GAME -> hiDisp && hiVol
            RegimeCategory.CROSS_REGIME -> true
        }
    }

    // ── §10.5 速查表 ──

    private val factorRuleMap: Map<String, SelectionRule> = mapOf(
        "A7" to SelectionRule("小盘领涨", "", 0, "Y2", 3, 0.0, listOf(
            StockFilter("turnoverAmount", "between", "0,10", "日成交额 < 10 亿"),
            StockFilter("pctChg", "rank_top_pct", "30", "今日涨幅前 30%"),
        ), "持仓 3 天；B4 < 0.45 离场", "F2a", 80),
        "B4" to SelectionRule("广度补涨", "", 0, "Y2", 3, 0.0, listOf(
            StockFilter("pctChg", "between", "0.01,0.03", "涨幅 1-3%"),
            StockFilter("pctChg3d", "<=", "0.05", "近 3 日涨幅 < 5%"),
            StockFilter("marketCap", "between", "20,200", "流通市值 20-200 亿"),
        ), "B4 < 0.45 或持仓满 3 天", "F2a", 120),
        "B5" to SelectionRule("跟涨标的", "", 0, "Y2", 3, 0.0, listOf(
            StockFilter("pctChg", "between", "0.03,0.05", "涨幅 3-5%"),
            StockFilter("volumeRatio", ">=", "1.2", "量比 ≥ 1.2"),
            StockFilter("marketCap", "between", "20,200", "流通市值 20-200 亿"),
        ), "B5 < 5% 或持仓满 3 天", "F2a", 30),
        "B6" to SelectionRule("错杀修复", "", 0, "Y2", 3, 0.0, listOf(
            StockFilter("pctChg3d", "<=", "-0.08", "近 3 日跌 > 8%"),
            StockFilter("isSt", "!=", "true", "非 ST"),
            StockFilter("roe", ">=", "0.05", "ROE ≥ 5%"),
            StockFilter("pctChg", ">=", "0.01", "今日收阳"),
        ), "持仓 3 天；再跌 3% 止损", "F1b", 20),
        "D3" to SelectionRule("趋势中段加速", "", 0, "Y1", 3, 0.0, listOf(
            StockFilter("pctChg5d", ">=", "0.03", "5 日涨幅 ≥ 3%"),
            StockFilter("pctChg20d", ">=", "0.05", "20 日涨幅 ≥ 5%"),
            StockFilter("isNewHigh5d", "==", "true", "今日创 5 日新高"),
        ), "D3 > +2σ 或持仓满 3 天", "F2a", 40),
        "D4" to SelectionRule("位置自适应", "", 0, "Y1", 3, 0.0, listOf(
            StockFilter("pctChg20d", "rank", "adaptive", "<20% 超跌反弹, 20-80% 趋势, >80% 强势延续"),
        ), "D4 > 50% 或持仓满 3 天", "F2a", 50),
        "A1" to SelectionRule("市场方向跟随", "", 0, "Y1", 3, 0.0, listOf(
            StockFilter("pctChg", ">=", "0.02", "涨幅 ≥ 2%"),
            StockFilter("volumeRatio", ">=", "1.0", "量比 ≥ 1.0"),
        ), "A1 连续 2 日转负或持仓满 3 天", "F2a", 100),
        "D1" to SelectionRule("趋势惯性", "", 0, "Y1", 3, 0.0, listOf(
            StockFilter("pctChg5d", ">=", "0.02", "5 日涨 ≥ 2%"),
            StockFilter("isNewHigh5d", "==", "true", "创 5 日新高"),
            StockFilter("marketCap", ">=", "100", "市值 ≥ 100 亿"),
        ), "D1 转负或持仓满 3-5 天", "F2a", 30),
        "D2" to SelectionRule("短多长多确认", "", 0, "Y1", 3, 0.0, listOf(
            StockFilter("pctChg5d", ">=", "0.02", "5 日涨 ≥ 2%"),
            StockFilter("pctChg20d", ">=", "0.05", "20 日涨 ≥ 5%"),
        ), "D1 < D2 或持仓满 3-5 天", "F2a", 25),
        "A2" to SelectionRule("加速动量", "", 0, "Y1", 3, 0.0, listOf(
            StockFilter("pctChg3d", ">=", "0.02", "3 日涨 ≥ 2%"),
            StockFilter("pctChg", ">=", "pctChgPrev", "今日 > 昨日涨幅"),
        ), "A2 转负或持仓满 3 天", "F1b", 40),
        "B7" to SelectionRule("广度持续改善", "", 0, "Y2", 3, 0.0, listOf(
            StockFilter("pctChg", ">=", "0.01", "涨幅 ≥ 1%"),
            StockFilter("pctChg3d", "<=", "0.05", "近 3 日 < 5%"),
            StockFilter("volumeRatio", ">=", "1.0", "量比 ≥ 1.0"),
        ), "B7 转负或持仓满 3 天", "F1b", 60),
        "A5" to SelectionRule("涨停接力", "", 0, "Y3", 1, 0.0, listOf(
            StockFilter("wasLimitUpYesterday", "==", "true", "昨日涨停非一字板"),
            StockFilter("openPctChg", "between", "0.02,0.05", "高开 2-5%"),
            StockFilter("marketCap", "<=", "50", "市值 < 50 亿"),
        ), "次日跌 > 2% 无条件离场", "F1b", 8),
        "A6" to SelectionRule("恐慌反向", "", 0, "Y3", 3, 0.0, listOf(
            StockFilter("pctChg3d", "<=", "-0.08", "近 3 日跌 > 8%"),
            StockFilter("pctChg", ">=", "0.01", "今日止跌收阳"),
            StockFilter("isSt", "!=", "true", "非 ST"),
        ), "持仓 3 天；再跌 5% 止损", "F1b", 12),
        "C4" to SelectionRule("涨停盈亏接力", "", 0, "Y1", 3, 0.0, listOf(
            StockFilter("wasLimitUpYesterday", "==", "true", "昨日涨停"),
            StockFilter("isLimitUpToday", "!=", "true", "今日未涨停可买入"),
            StockFilter("openPctChg", ">=", "0.0", "高开或不低开"),
        ), "C4 转负或持仓满 3 天", "F2a", 5),
    )

    private fun positionRatio(avgCoherence: Double): Double = when {
        avgCoherence >= 0.8 -> 0.9
        avgCoherence >= 0.7 -> 0.7
        avgCoherence >= 0.6 -> 0.5
        else -> 0.3
    }

    private fun poolSize(factor: String): Int = when (factor) {
        "A7" -> 80; "B4" -> 120; "B5" -> 30; "B6" -> 20; "D3" -> 40
        "D4" -> 50; "A1" -> 100; "D1" -> 30; "D2" -> 25; "A2" -> 40
        "B7" -> 60; "A5" -> 8; "A6" -> 12; "C4" -> 5
        else -> 30
    }
}
