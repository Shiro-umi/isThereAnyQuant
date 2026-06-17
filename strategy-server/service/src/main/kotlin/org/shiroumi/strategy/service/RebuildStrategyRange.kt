package org.shiroumi.strategy.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.strategy.service.postmarket.PostMarketOrchestrator
import utils.logger

private val logger by logger("RebuildStrategyRange")

/**
 * 盘后策略全链路区间重建工具。
 *
 * 走 [PostMarketOrchestrator.rebuildTradeDatesCatching]（在 executeTradeDatesCatching 全链编排
 * ——因子/情绪/模型选股/审计/持仓状态机/seed 逐日推进——之上叠加重建专属语义）：
 * 1. 区间强制清表：逐日推进前清空 [start..end] 持仓残留旧链。
 * 2. 逐日严格串行：previousHoldings 链依赖前一交易日，不并行/乱序。
 * 3. 滑窗日线供给器：分段预取替代逐日两次按交易日查库，消除「同日被扫两次」2 倍冗余。
 * 4. selection 复现断言：覆盖 selection 前与历史落库逐票比对（[SelectionDriftGuard]），漂移默认拒绝。
 *
 * 以独立 JVM 运行，适合换模型或换持仓规则后的历史刷库。
 *
 * 运行参数（系统属性）：
 * - `quant.strategy.rebuild.start` / `quant.strategy.rebuild.end`：重建区间（闭区间，自然日，内部解析为交易日）
 * - `quant.strategy.rebuild.allowSelectionDrift`：默认 false。selection 复现失败时是否放行漂移落库；
 *   换模型/换候选口径的有意重建需显式置 true。
 * - `quant.strategy.holding.*`：持仓规则与破位加分开关（如 breakdownRerank），由 gradle 任务白名单透传。
 * - `quant.profitPrediction.servicePort`：必须传隔离端口，避免复用在线服务的推理进程
 *
 * 重建完成后在线 strategy-service 的内存快照仍是旧的，需要重启 service 或等待下一次盘后任务刷新。
 */
fun main() = runBlocking {
    Class.forName("com.mysql.cj.jdbc.Driver")
    ConfigManager.load()

    val start = LocalDate.parse(System.getProperty("quant.strategy.rebuild.start") ?: error("缺少 quant.strategy.rebuild.start"))
    val end = LocalDate.parse(System.getProperty("quant.strategy.rebuild.end") ?: error("缺少 quant.strategy.rebuild.end"))
    require(end >= start) { "无效区间: start=$start end=$end" }

    val tradeDates = TradingCalendarRepository.findOpenDates(start, end)
    logger.info("[策略区间重建] start=$start end=$end 交易日=${tradeDates.size}")

    val failures = mutableListOf<String>()
    // 全历史重建走 rebuild 入口：先清空 [start..end] 持仓残留旧链，再注入滑窗供给器逐日严格串行重算。
    val result = PostMarketOrchestrator.rebuildTradeDatesCatching(
        tradeDates = tradeDates,
        onTradeDateFailure = { tradeDate, error ->
            failures += "tradeDate=$tradeDate error=${error.message}"
        },
    )

    logger.info(
        "[策略区间重建] finished processed=${result.processedDates.size}/${tradeDates.size} " +
            "failedDate=${result.failedDate ?: "无"}"
    )
    failures.forEach { println("[strategy-rebuild] $it") }
    println("[strategy-rebuild] processed=${result.processedDates.size}/${tradeDates.size} failed=${if (result.failedDate != null) 1 else 0}")
    if (result.failedDate != null) {
        kotlin.system.exitProcess(1)
    }
}
