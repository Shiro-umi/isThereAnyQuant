package org.shiroumi.strategy.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.strategy.core.daily.TargetPosition
import utils.logger
import java.io.File

/**
 * 把融合模型的 walk-forward OOS 打分(Top3/日,已由 Python 导出为 CSV)导入 daily_profit_prediction_selection,
 * 供回测以生产口径消费。不重新推理,直接用已训好的 OOS 分。
 *
 * CSV 格式(temp/fusion_selection_top3.csv): pred_date(int YYYYMMDD), ts_code, score, rank
 * 写表语义(对齐生产 BackfillProfitPredictionSelections):
 *  - trade_date = pred_date(信号日 T)
 *  - target_date = T 的下一交易日(执行日,对齐 open[T+1] 建仓)
 *  - target_weight = 1/3(Top3 等权,贴生产实盘"等权模型分 Top3 等权")
 *  - selectionReason = "profit-prediction-7pct:fusion-v1:all-universe:score=..." → 解析出 model_id=fusion-v1
 *  - 换模型必清表:逐日 deleteByDate 再 replaceForDate
 *
 * 运行: ./gradlew :strategy-server:service:importFusionScores -Dquant.fusion.csv=temp/fusion_selection_top3.csv
 */
private val logger by logger("ImportFusionScoresToSelection")

fun main() = runBlocking {
    Class.forName("com.mysql.cj.jdbc.Driver")
    ConfigManager.load()

    val csvPath = System.getProperty("quant.fusion.csv", "temp/fusion_selection_top3.csv")
    val modelId = System.getProperty("quant.fusion.modelId", "fusion-v1")
    val weight = 1.0 / 3.0

    val lines = File(csvPath).readLines().drop(1).filter { it.isNotBlank() }
    // 按 pred_date 分组
    val byDate = linkedMapOf<Int, MutableList<Pair<String, Double>>>()
    for (line in lines) {
        val p = line.split(",")
        val predDate = p[0].trim().toInt()
        val tsCode = p[1].trim()
        val score = p[2].trim().toDouble()
        byDate.getOrPut(predDate) { mutableListOf() }.add(tsCode to score)
    }
    logger.info("[fusion-import] csv=$csvPath days=${byDate.size} modelId=$modelId")

    var success = 0
    val failures = mutableListOf<String>()
    for ((predDateInt, picks) in byDate) {
        val tradeDate = LocalDate.parse(
            "${predDateInt / 10000}-${"%02d".format(predDateInt / 100 % 100)}-${"%02d".format(predDateInt % 100)}"
        )
        val targetDate = TradingCalendarRepository.findNextTradingDate(tradeDate) ?: continue
        runCatching {
            val positions = picks.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                .map { (tsCode, score) ->
                    TargetPosition(
                        tradeDate = tradeDate,
                        targetDate = targetDate,
                        tsCode = tsCode,
                        selectionScore = score,
                        selected = true,
                        targetWeight = weight,
                        sentimentExposure = 1.0,
                        selectionReason = "profit-prediction-7pct:$modelId:all-universe:score=${"%.6f".format(score)}",
                    )
                }
            DailyProfitPredictionSelectionRepository.deleteByDate(tradeDate)
            DailyProfitPredictionSelectionRepository.replaceForDate(tradeDate, positions)
            success += 1
        }.onFailure { e ->
            failures += "tradeDate=$tradeDate error=${e.message}"
            logger.error("[fusion-import] failed tradeDate=$tradeDate ${e.message}")
        }
    }
    logger.info("[fusion-import] finished success=$success failed=${failures.size}")
    println("[fusion-import] success=$success failed=${failures.size}")
    failures.take(10).forEach { println("[fusion-import] $it") }
}
