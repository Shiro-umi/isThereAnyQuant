package org.shiroumi.server

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.strategy.daily.DailyStrategyRecoveryService
import kotlin.system.exitProcess
import kotlin.time.Clock

fun main() {
    try {
        Class.forName("com.mysql.cj.jdbc.Driver")
    } catch (_: Exception) {}
    try {
        Class.forName("org.h2.Driver")
    } catch (_: Exception) {}

    ConfigManager.load()

    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val tradeDate = parseTradeDate(today)
    val dryRun = java.lang.System.getProperty("quant.cleanup.dryRun")?.toBooleanStrictOrNull() ?: false

    println("🧹 开始清理策略产物 | tradeDate=$tradeDate, dryRun=$dryRun")
    val result = DailyStrategyRecoveryService.cleanup(tradeDate = tradeDate, dryRun = dryRun)
    println("📊 清理前摘要:")
    printSummary(result.before)
    println("📊 清理后摘要:")
    printSummary(result.after)
    println(
        "📝 本次影响: marketSentiment=${result.deletedMarketSentiment}, " +
            "marketSentimentState=${result.deletedMarketSentimentState}, stockFactor=${result.deletedStockFactor}, " +
            "factorRollingState=${result.deletedFactorRollingState}, targetPortfolio=${result.deletedTargetPortfolio}, " +
            "strategyAudit=${result.deletedStrategyAudit}, runtimeSeeds=${result.deletedRuntimeSeeds}"
    )
    if (dryRun) {
        println("ℹ️ dry-run 模式未真正删除数据。")
    } else {
        println("✅ 清理完成。下一步可执行 ./gradlew :ktor-server:runDataUpdate 重新跑当天盘后任务。")
    }
}

private fun parseTradeDate(today: LocalDate): LocalDate {
    val raw = java.lang.System.getProperty("quant.cleanup.tradeDate") ?: today.toString()
    return runCatching { LocalDate.parse(raw) }
        .getOrElse {
            println("❌ 非法 tradeDate: $raw")
            exitProcess(1)
        }
}

private fun printSummary(summary: org.shiroumi.database.strategy.daily.DailyStrategyRecoverySummary) {
    println(
        "   tradeDate=${summary.tradeDate}, marketSentiment=${summary.marketSentimentCount}, " +
            "marketSentimentState=${summary.marketSentimentStateCount}, stockFactor=${summary.stockFactorCount}, " +
            "factorRollingState=${summary.factorRollingStateCount}, targetPortfolio=${summary.targetPortfolioCount}, " +
            "strategyAudit=${summary.strategyAuditCount}, runtimeSeeds=${summary.runtimeSeedCount}, " +
            "strategyUpdated=${summary.strategyUpdated}"
    )
}
