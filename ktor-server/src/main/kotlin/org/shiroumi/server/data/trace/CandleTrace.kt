package org.shiroumi.server.data.trace

import model.candle.CandlePeriod
import org.shiroumi.config.ConfigManager
import utils.logger

/**
 * 服务端 DAY K 线订阅链路 trace。
 *
 * 设计意图：
 * - 只在 debug 部署开启，避免 release 被完整链路日志淹没。
 * - 只追踪 DAY，保持这次排查聚焦在真实业务场景里的日线主链。
 * - `requestSeq` 是链路关联键，日志格式保持稳定，便于 grep。
 */
object CandleTrace {
    private val traceLogger by logger("CandleTrace")

    fun enabled(period: CandlePeriod): Boolean =
        period == CandlePeriod.DAY &&
            runCatching { ConfigManager.getConfig().server.testMode }.getOrDefault(false)

    fun log(
        stage: String,
        tsCode: String,
        period: CandlePeriod,
        requestSeq: Long?,
        detail: String
    ) {
        if (!enabled(period)) return
        traceLogger.info(
            "[TRACE][$stage] tsCode=$tsCode, period=${period.name}, requestSeq=$requestSeq, $detail"
        )
    }
}
