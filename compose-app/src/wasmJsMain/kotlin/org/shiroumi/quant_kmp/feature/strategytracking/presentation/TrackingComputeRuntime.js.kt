@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.shiroumi.quant_kmp.feature.strategytracking.presentation

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import org.shiroumi.quant_kmp.AppJson
import kotlin.coroutines.resume
import kotlin.js.JsAny

private fun isWorkerSupported(): Boolean = js("typeof Worker !== 'undefined'")

private fun createWorker(
    scriptUrl: String,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
): JsAny = js(
    """{
        const worker = new Worker(scriptUrl);
        worker.onmessage = (event) => onMessage(JSON.stringify(event.data));
        worker.onerror = (event) => onError(event && event.message ? event.message : "worker error");
        return worker;
    }"""
)

private fun postWorkerMessage(worker: JsAny, payloadJson: String): Unit = js(
    "{ worker.postMessage(JSON.parse(payloadJson)); }"
)

private fun terminateWorker(worker: JsAny): Unit = js(
    "{ worker.terminate(); }"
)

@Serializable
private data class WorkerRequest(
    val requestId: String,
    val taskType: String,
    val tradeDates: List<String>,
    val edges: List<WorkerEdge>,
)

@Serializable
private data class WorkerEdge(
    val fromDate: String,
    val fromSection: String,
    val fromSlotIndex: Int,
    val toDate: String,
    val toSection: String,
    val toSlotIndex: Int,
    val kind: String,
)

@Serializable
private data class WorkerResponse(
    val requestId: String? = null,
    val success: Boolean = false,
    val error: String? = null,
    val indexedEdges: List<WorkerIndexedEdge>? = null,
)

@Serializable
private data class WorkerIndexedEdge(
    val kind: String,
    val fromSection: String,
    val fromSlotIndex: Int,
    val toSection: String,
    val toSlotIndex: Int,
    val fromIndex: Int,
    val toIndex: Int,
)

private data class PendingTrackingRequest(
    val input: TrackingEdgeLayoutInput,
    val continuation: CancellableContinuation<TrackingEdgeLayoutResult>,
)

private object JsPlatformComputeRuntime : PlatformComputeRuntime {
    private val pendingRequests = mutableMapOf<String, PendingTrackingRequest>()
    private var worker: JsAny? = null
    private var nextRequestId = 0L

    override suspend fun buildTrackingEdgeLayout(input: TrackingEdgeLayoutInput): TrackingEdgeLayoutResult {
        val activeWorker = runCatching { ensureWorker() }.getOrElse { error ->
            logWorkerWarning("tracking compute worker unavailable, fallback to main thread:", error)
            return buildTrackingEdgeLayoutSync(input)
        }

        val requestId = (++nextRequestId).toString()

        return suspendCancellableCoroutine { continuation ->
            pendingRequests[requestId] = PendingTrackingRequest(
                input = input,
                continuation = continuation,
            )
            continuation.invokeOnCancellation {
                pendingRequests.remove(requestId)
            }

            runCatching {
                val request = WorkerRequest(
                    requestId = requestId,
                    taskType = "BuildTrackingEdgeLayout",
                    tradeDates = input.tradeDates,
                    edges = input.edges.map { edge ->
                        WorkerEdge(
                            fromDate = edge.fromDate,
                            fromSection = edge.fromSection.name,
                            fromSlotIndex = edge.fromSlotIndex,
                            toDate = edge.toDate,
                            toSection = edge.toSection.name,
                            toSlotIndex = edge.toSlotIndex,
                            kind = edge.kind.name,
                        )
                    },
                )
                postWorkerMessage(activeWorker, AppJson.encodeToString(WorkerRequest.serializer(), request))
            }.onFailure { error ->
                pendingRequests.remove(requestId)
                logWorkerWarning("tracking compute worker postMessage failed, fallback to main thread:", error)
                continuation.resume(buildTrackingEdgeLayoutSync(input))
            }
        }
    }

    private fun ensureWorker(): JsAny {
        worker?.let { return it }
        check(isWorkerSupported()) {
            "Web Worker is not supported in this browser"
        }

        val createdWorker = createWorker(
            scriptUrl = "tracking-compute-worker.js",
            onMessage = ::handleWorkerMessage,
            onError = ::handleWorkerError,
        )
        worker = createdWorker
        return createdWorker
    }

    private fun handleWorkerMessage(payloadJson: String) {
        val response = runCatching {
            AppJson.decodeFromString(WorkerResponse.serializer(), payloadJson)
        }.getOrElse { error ->
            logWorkerWarning("tracking compute worker decode failed:", error)
            return
        }
        val requestId = response.requestId ?: return
        val pending = pendingRequests.remove(requestId) ?: return

        if (!response.success) {
            logWorkerWarning("tracking compute worker task failed, fallback to main thread:", response.error)
            if (pending.continuation.isActive) {
                pending.continuation.resume(buildTrackingEdgeLayoutSync(pending.input))
            }
            return
        }

        val indexedEdges = response.indexedEdges
        if (indexedEdges == null) {
            if (pending.continuation.isActive) {
                pending.continuation.resume(buildTrackingEdgeLayoutSync(pending.input))
            }
            return
        }

        val result = runCatching {
            TrackingEdgeLayoutResult(
                indexedEdges = indexedEdges.map { edge ->
                    IndexedTrackingEdgePayload(
                        kind = model.candle.StrategyTrackingEdgeKind.valueOf(edge.kind),
                        fromSection = model.candle.StrategyTrackingSection.valueOf(edge.fromSection),
                        fromSlotIndex = edge.fromSlotIndex,
                        toSection = model.candle.StrategyTrackingSection.valueOf(edge.toSection),
                        toSlotIndex = edge.toSlotIndex,
                        fromIndex = edge.fromIndex,
                        toIndex = edge.toIndex,
                    )
                }
            )
        }.getOrElse { error ->
            logWorkerWarning("tracking compute worker result mapping failed, fallback to main thread:", error)
            buildTrackingEdgeLayoutSync(pending.input)
        }
        if (pending.continuation.isActive) {
            pending.continuation.resume(result)
        }
    }

    private fun handleWorkerError(message: String) {
        logWorkerWarning("tracking compute worker crashed, fallback to main thread:", message)
        worker?.let { runCatching { terminateWorker(it) } }
        worker = null
        val requests = pendingRequests.values.toList()
        pendingRequests.clear()
        requests.forEach { pending ->
            if (pending.continuation.isActive) {
                pending.continuation.resume(buildTrackingEdgeLayoutSync(pending.input))
            }
        }
    }
}

internal actual fun platformComputeRuntime(): PlatformComputeRuntime = JsPlatformComputeRuntime

private fun logWorkerWarning(message: String, error: Any?) {
    println("$message $error")
}
