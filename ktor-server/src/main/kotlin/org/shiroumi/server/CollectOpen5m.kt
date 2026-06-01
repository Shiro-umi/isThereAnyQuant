package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import org.shiroumi.config.ConfigManager
import org.shiroumi.server.runtime.update.Open5mSyncService

/**
 * 每日首根 5min K 线**全量回填**入口（standalone，复用并发 [Open5mSyncService]，不动生产 server）。
 *
 * 与盘后主链路（HistoricalDataUpdateOrchestrator「更新开盘5min数据」步）共用同一并发采集实现，
 * 这里仅作一次性全量回填的命令行入口。幂等可断点续跑。
 *
 * 参数（system props）：
 *  - quant.open5m.fromYear（默认 2010，stk_mins 实测起点）/ quant.open5m.toYear（默认当前年）
 *  - quant.open5m.symbolLimit（默认 0=全量；小样本验证设小值）
 *  - quant.open5m.concurrency（默认 50）
 *
 * 运行：./gradlew :ktor-server:collectOpen5m -Dquant.open5m.fromYear=2010
 */
fun main() = runBlocking {
    ConfigManager.load()
    val fromYear = System.getProperty("quant.open5m.fromYear")?.toIntOrNull() ?: 2010
    val toYear = System.getProperty("quant.open5m.toYear")?.toIntOrNull()
    val symbolLimit = System.getProperty("quant.open5m.symbolLimit")?.toIntOrNull() ?: 0
    val concurrency = System.getProperty("quant.open5m.concurrency")?.toIntOrNull() ?: 50
    Open5mSyncService(concurrency = concurrency).backfill(fromYear = fromYear, toYear = toYear, symbolLimit = symbolLimit)
}
