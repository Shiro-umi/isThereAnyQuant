package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object IosPlatformComputeRuntime : PlatformComputeRuntime {
    override suspend fun buildTrackingEdgeLayout(input: TrackingEdgeLayoutInput): TrackingEdgeLayoutResult =
        withContext(Dispatchers.Default) {
            buildTrackingEdgeLayoutSync(input)
        }
}

internal actual fun platformComputeRuntime(): PlatformComputeRuntime = IosPlatformComputeRuntime
