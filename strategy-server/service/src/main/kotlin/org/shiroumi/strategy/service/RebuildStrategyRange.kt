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
 * 与 strategy-service 处理 `RebuildRange` 指令走完全相同的编排
 * （[PostMarketOrchestrator.executeTradeDatesCatching]：因子/情绪/模型选股/审计/持仓状态机/seed 逐日推进），
 * 以独立 JVM 运行，适合换模型或换持仓规则后的历史刷库。
 *
 * 运行参数（系统属性）：
 * - `quant.strategy.rebuild.start` / `quant.strategy.rebuild.end`：重建区间（闭区间，自然日，内部解析为交易日）
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
    val result = PostMarketOrchestrator.executeTradeDatesCatching(
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
