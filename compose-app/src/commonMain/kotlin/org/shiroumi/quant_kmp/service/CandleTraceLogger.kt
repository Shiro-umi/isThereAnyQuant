package org.shiroumi.quant_kmp.service

import model.candle.CandlePeriod
import org.shiroumi.config.AppConfig

/**
 * 前端 DAY K 线订阅链路 trace。
 *
 * 约束：
 * - 只在 debug / testMode 下输出完整链路，避免 release 日志噪音。
 * - 当前只覆盖 DAY，先把用户正在追踪的日线链路打透。
 * - 使用 `requestSeq` 作为跨前后端的关联键，方便一条命令从发送到落状态完整追踪。
 */
object CandleTraceLogger {
    private fun enabled(period: CandlePeriod): Boolean =
        AppConfig.testMode && period == CandlePeriod.DAY

    fun log(
        stage: String,
        tsCode: String,
        period: CandlePeriod,
        requestSeq: Long?,
        detail: String
    ) {
        if (!enabled(period)) return
        println(
            "[CANDLE_TRACE][FRONTEND][$stage] " +
                "tsCode=$tsCode, period=${period.name}, requestSeq=$requestSeq, $detail"
        )
    }
}
