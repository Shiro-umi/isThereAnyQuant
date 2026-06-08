package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import org.shiroumi.config.ConfigManager
import org.shiroumi.server.runtime.update.Stock15mSyncService

/**
 * 股票 15min K 线全量回填入口（standalone，不进入 Ktor 正常启动链路）。
 * Tushare `stk_mins` 的业务粒度是 `ts_code + freq + start_date + end_date`，
 * 不支持按单个交易日一次返回全市场分钟线；本入口因此按“股票 × 年度窗口”遍历。
 *
 * 参数（system props）：
 *  - quant.stock15m.fromYear（默认 2000；Tushare stk_mins 早期年份可能无分钟数据）
 *  - quant.stock15m.toYear（默认当前年）
 *  - quant.stock15m.symbolLimit（默认 0=stock_info 全量）
 *  - quant.stock15m.concurrency（默认 30）
 *  - quant.stock15m.requestIntervalMillis（默认 350；全局请求节流，避免 stk_mins 500次/分钟频控）
 *  - quant.stock15m.rateLimitCooldownMillis（默认 120000；命中 40203 后的初始冷却）
 *  - quant.stock15m.maxRateLimitCooldownMillis（默认 600000；连续 40203 退避冷却上限）
 *  - quant.stock15m.progressEverySymbols（默认 50；每完成 N 只股票输出一次总体进度）
 *  - quant.stock15m.detailedLog（默认 false；true 时输出单股/年度窗口诊断日志）
 *  - quant.stock15m.skipExistingWindows（默认 true）
 *  - quant.stock15m.shardIndex / quant.stock15m.shardCount（默认 0/1；按 stock_info 顺序分片）
 *
 * 运行：
 * ./gradlew :ktor-server:collectStock15m -Dquant.stock15m.fromYear=2000
 */
fun main() = runBlocking {
    ConfigManager.load()
    val fromYear = System.getProperty("quant.stock15m.fromYear")?.toIntOrNull() ?: 2000
    val toYear = System.getProperty("quant.stock15m.toYear")?.toIntOrNull()
    val symbolLimit = System.getProperty("quant.stock15m.symbolLimit")?.toIntOrNull() ?: 0
    val concurrency = System.getProperty("quant.stock15m.concurrency")?.toIntOrNull() ?: 30
    val requestIntervalMillis = System.getProperty("quant.stock15m.requestIntervalMillis")?.toLongOrNull() ?: 350L
    val rateLimitCooldownMillis = System.getProperty("quant.stock15m.rateLimitCooldownMillis")?.toLongOrNull() ?: 120_000L
    val maxRateLimitCooldownMillis =
        System.getProperty("quant.stock15m.maxRateLimitCooldownMillis")?.toLongOrNull() ?: 600_000L
    val progressEverySymbols = System.getProperty("quant.stock15m.progressEverySymbols")?.toIntOrNull() ?: 50
    val detailedLog = System.getProperty("quant.stock15m.detailedLog")?.toBooleanStrictOrNull() ?: false
    val skipExistingWindows = System.getProperty("quant.stock15m.skipExistingWindows")
        ?.toBooleanStrictOrNull() ?: true
    val shardIndex = System.getProperty("quant.stock15m.shardIndex")?.toIntOrNull() ?: 0
    val shardCount = System.getProperty("quant.stock15m.shardCount")?.toIntOrNull() ?: 1
    Stock15mSyncService(
        concurrency = concurrency,
        requestIntervalMillis = requestIntervalMillis,
        rateLimitCooldownMillis = rateLimitCooldownMillis,
        maxRateLimitCooldownMillis = maxRateLimitCooldownMillis,
        progressEverySymbols = progressEverySymbols,
        detailedLog = detailedLog,
    ).backfill(
        fromYear = fromYear,
        toYear = toYear,
        symbolLimit = symbolLimit,
        skipExistingWindows = skipExistingWindows,
        shardIndex = shardIndex,
        shardCount = shardCount,
    )
}
