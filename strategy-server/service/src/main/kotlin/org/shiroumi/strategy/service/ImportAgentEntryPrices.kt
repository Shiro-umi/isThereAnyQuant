package org.shiroumi.strategy.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.strategy.daily.StrategyStateSchemaBootstrap
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import utils.logger
import java.io.File

/**
 * 把 agent 量价分析买点（`{agentEntryDir}/{executionDate}.json`）回填到
 * daily_profit_prediction_selection 的 limit_price 列。
 *
 * 日期键对齐：产物文件名 = executionDate = 选股表的 target_date（= 执行/建仓日 T+1）；
 * 只回填 limit_price，不动 model_score/selection_reason 等已落库选股事实（[DailyProfitPredictionSelectionRepository.updateLimitPrice]）。
 *
 * 每个文件的决策只采纳 side=BUY、hint=LIMIT、limitPrice 非空的条目，与
 * [org.shiroumi.backtest.feed.AgentEntryPriceFeed] 口径一致。同一 (target_date, ts_code) 取首个买点。
 *
 * 运行: ./gradlew :strategy-server:service:importAgentEntryPrices -Dquant.agentEntry.dir=temp/agent-entry-prices/run60
 */
private val logger by logger("ImportAgentEntryPrices")

fun main() = runBlocking {
    Class.forName("com.mysql.cj.jdbc.Driver")
    ConfigManager.load()
    // 确保 limit_price 列已存在（createMissingTablesAndColumns 自动 ADD nullable 列）。
    StrategyStateSchemaBootstrap.ensureSchema()

    val dir = System.getProperty("quant.agentEntry.dir") ?: error("缺少 quant.agentEntry.dir")
    val files = File(dir).listFiles { f -> f.isFile && f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.json")) }
        ?.sortedBy { it.name }
        ?: error("目录无买点文件: $dir")
    logger.info("[entry-import] dir=$dir files=${files.size}")

    val json = Json { ignoreUnknownKeys = true }
    var updated = 0
    var missed = 0
    val misses = mutableListOf<String>()
    for (file in files) {
        val targetDate = LocalDate.parse(file.nameWithoutExtension)
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val decisions = root["decisions"]?.jsonArray ?: continue
        val seen = mutableSetOf<String>()
        for (d in decisions) {
            val o = d.jsonObject
            if (o["side"]?.jsonPrimitive?.content != "BUY") continue
            if (o["hint"]?.jsonPrimitive?.content != "LIMIT") continue
            val tsCode = o["tsCode"]?.jsonPrimitive?.content ?: continue
            val limit = o["limitPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
            if (!seen.add(tsCode)) continue // 同票取首个买点
            val rows = DailyProfitPredictionSelectionRepository.updateLimitPrice(targetDate, tsCode, limit)
            if (rows > 0) {
                updated += 1
            } else {
                missed += 1
                misses += "$targetDate $tsCode（selection 表无 selected 行）"
            }
        }
    }
    logger.info("[entry-import] finished updated=$updated missed=$missed")
    println("[entry-import] updated=$updated missed=$missed")
    misses.take(10).forEach { println("[entry-import] miss $it") }
}
