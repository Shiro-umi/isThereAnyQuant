package org.shiroumi.quant_kmp.feature.strategytracking.presentation

/**
 * 最早跟随日校准状态机的纯逻辑。
 *
 * 校准态 [TrackingCalibration] 的生命周期由用户意图主导（激活 / 清除），WebSocket 帧只能填充或
 * 翻转，不能擅自清空用户的校准声明。把易错的翻转规则从协程 IO 中剥离为纯函数，便于单测覆盖
 * 「ERROR 帧 / 校准成功帧 / 模型原始帧」三类输入在各前置态下的输出，杜绝竞态导致 loading 永挂或
 * 错误一闪而过。
 */
internal object CalibrationStateReducer {

    /**
     * 收到订阅 ERROR 帧后的校准态。
     *
     * - 校准等待期（isLoading）：把错误落到校准态，解除 loading，保留校准入口供用户重选日期。
     * - 已成功的校准（isLoading=false 且 error=null）：忽略迟到 / 重发的 ERROR，不回退成功态。
     * - 已是错误态：保持原错误（幂等）。
     *
     * 返回 null 表示当前非校准态（调用方应改写主错误流），不在此函数职责内。
     */
    fun onError(current: TrackingCalibration?, message: String): TrackingCalibration? {
        if (current == null) return null
        return if (current.isLoading) {
            current.copy(isLoading = false, error = message)
        } else {
            current
        }
    }

    /**
     * 收到一帧持仓跟踪响应后的校准态。
     *
     * - 校准成功帧（[wsFollowStartDate] 非空）：填充为成功态。
     * - 模型原始帧（[wsFollowStartDate] 为空）：仅当校准处于「稳定成功」态（非 loading 且无 error）时
     *   才回退模型流（返回 null），代表后端确已切回模型流；校准等待期与失败展示期一律保留，避免冲掉
     *   等待中的请求或刚落下的失败原因（失败时后端会紧跟一帧原始快照）。
     */
    fun onResponse(
        current: TrackingCalibration?,
        wsFollowStartDate: String?,
        timeline: StrategyPositionTrackingTimeline,
    ): TrackingCalibration? {
        if (wsFollowStartDate != null) {
            return TrackingCalibration(
                followStartDate = wsFollowStartDate,
                timeline = timeline,
                isLoading = false,
                error = null,
            )
        }
        return if (current != null && !current.isLoading && current.error == null) {
            null
        } else {
            current
        }
    }
}
