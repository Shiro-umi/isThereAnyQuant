package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalibrationStateReducerTest {

    private val emptyTimeline = StrategyPositionTrackingTimeline(days = emptyList(), edges = emptyList())

    private fun loading(date: String = "2026-05-20") =
        TrackingCalibration(followStartDate = date, timeline = null, isLoading = true, error = null)

    private fun success(date: String = "2026-05-20") =
        TrackingCalibration(followStartDate = date, timeline = emptyTimeline, isLoading = false, error = null)

    private fun failed(date: String = "2026-05-20", message: String = "该跟随起始日无法校准") =
        TrackingCalibration(followStartDate = date, timeline = null, isLoading = false, error = message)

    // onError —— ERROR 帧

    @Test
    fun onError_duringLoading_setsErrorAndStopsLoading() {
        val next = CalibrationStateReducer.onError(loading(), "该跟随起始日无法校准")
        assertEquals(false, next?.isLoading)
        assertEquals("该跟随起始日无法校准", next?.error)
        assertEquals("2026-05-20", next?.followStartDate)
    }

    @Test
    fun onError_onSuccessfulCalibration_isIgnored() {
        // 迟到/重发的 ERROR 不得把已成功的校准翻成失败态
        val stable = success()
        val next = CalibrationStateReducer.onError(stable, "迟到错误")
        assertEquals(stable, next)
    }

    @Test
    fun onError_whenNotCalibrating_returnsNull() {
        assertNull(CalibrationStateReducer.onError(null, "service 不可用"))
    }

    // onResponse —— 持仓跟踪响应帧

    @Test
    fun onResponse_withFollowStartDate_fillsSuccessState() {
        val next = CalibrationStateReducer.onResponse(
            current = loading(),
            wsFollowStartDate = "2026-05-20",
            timeline = emptyTimeline,
        )
        assertEquals("2026-05-20", next?.followStartDate)
        assertEquals(false, next?.isLoading)
        assertNull(next?.error)
        assertEquals(emptyTimeline, next?.timeline)
    }

    @Test
    fun onResponse_originalFrame_duringLoading_keepsCalibration() {
        // 校准等待期内先到的模型原始帧不得清空校准，应继续等待校准帧或 ERROR 帧
        val pending = loading()
        val next = CalibrationStateReducer.onResponse(pending, wsFollowStartDate = null, timeline = emptyTimeline)
        assertEquals(pending, next)
    }

    @Test
    fun onResponse_originalFrame_onErrorState_keepsErrorVisible() {
        // 校准失败后后端紧跟一帧原始快照，不得在此清空 error，否则失败原因一闪而过
        val errored = failed()
        val next = CalibrationStateReducer.onResponse(errored, wsFollowStartDate = null, timeline = emptyTimeline)
        assertEquals(errored, next)
    }

    @Test
    fun onResponse_originalFrame_onStableSuccess_fallsBackToModelStream() {
        // 校准稳定成功后收到原始帧 = 后端确已切回模型流，回退为 null
        val next = CalibrationStateReducer.onResponse(success(), wsFollowStartDate = null, timeline = emptyTimeline)
        assertNull(next)
    }

    @Test
    fun onResponse_originalFrame_whenNotCalibrating_staysNull() {
        val next = CalibrationStateReducer.onResponse(null, wsFollowStartDate = null, timeline = emptyTimeline)
        assertNull(next)
    }
}
