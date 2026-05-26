package org.shiroumi.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Tushare 全局限频器 —— 按 token + 接口名（api_name）分桶。
 *
 * 这里使用“严格令牌桶”，但禁止突发积攒：
 * - 每个接口独立分桶
 * - 同一个 token 下共享该接口额度
 * - 桶容量固定为 1，空闲后不会攒出一波突发请求
 *
 * 这样 `rt_k` 这类“每分钟最多 50 次”的接口会被稳定地匀速放行，
 * 避免多个调用源在同一 token 下偶发踩到 Tushare 的真实计数窗口。
 *
 * 在 network 模块的 API 出口处（TuShareApiImpl.query）统一限频，
 * 保证无论哪个业务方调用 tushare 接口，都受到对应接口的频率约束。
 *
 * 为什么不在业务层限频：
 * 1. 业务层调用粒度不一致（一个"逻辑请求"可能触发 1~3 次实际 API 调用）
 * 2. 新增业务方容易遗漏限频
 * 3. 多个独立限频器无法约束全局配额
 */
object TushareRateLimiter {
    /**
     * 每分钟允许的最大 API 调用次数（每个接口独立）。
     * 与 Tushare 官方配额保持一致。
     */
    private const val FREQUENCY_PER_MINUTE = 400
    private const val MINUTE_MS = 60_000L

    private val buckets = ConcurrentHashMap<BucketKey, ApiRateBucket>()

    suspend fun acquire(apiName: String, token: String) {
        val bucket = buckets.computeIfAbsent(BucketKey(apiName = apiName, token = token)) {
            val permitsPerMinute = if (apiName == "rt_k") 50 else FREQUENCY_PER_MINUTE
            ApiRateBucket(permitsPerMinute)
        }
        bucket.acquire()
    }

    private data class BucketKey(
        val apiName: String,
        val token: String
    )

    private class ApiRateBucket(
        private val permitsPerMinute: Int
    ) {
        private val mutex = Mutex()
        private val refillIntervalMs = (MINUTE_MS.toDouble() / permitsPerMinute.toDouble()).toLong().coerceAtLeast(1L)
        private val capacity = 1.0
        private var tokens = capacity
        private var lastRefillAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds()

        suspend fun acquire() {
            while (true) {
                val waitTime = mutex.withLock {
                    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    refill(now)
                    if (tokens >= 1.0) {
                        tokens -= 1.0
                        0L
                    } else {
                        val missingTokens = 1.0 - tokens
                        kotlin.math.ceil(missingTokens * refillIntervalMs).toLong().coerceAtLeast(1L)
                    }
                }
                if (waitTime <= 0L) {
                    return
                }
                delay(waitTime)
            }
        }

        private fun refill(nowMs: Long) {
            val elapsedMs = nowMs - lastRefillAtMs
            if (elapsedMs <= 0L) return

            val refilled = elapsedMs.toDouble() / refillIntervalMs.toDouble()
            if (refilled > 0.0) {
                tokens = (tokens + refilled).coerceAtMost(capacity)
                lastRefillAtMs = nowMs
            }
        }
    }
}
