package org.shiroumi.server.data.bootstrap

import org.shiroumi.server.data.api.CandleApiLayer
import org.shiroumi.server.data.facade.CandleDataFacade
import org.shiroumi.server.data.provider.CandleDataProvider
import org.shiroumi.server.data.snapshot.CandleSnapshotManager
import org.shiroumi.server.data.subscription.CandleProjectionService
import org.shiroumi.server.data.subscription.CandleSubscriptionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 新 `server.data.*` Candle 主链的统一装配入口。
 *
 * 这里的目标不是“再造一个大而全的 bootstrap”，而是：
 * - 让新数据层有清晰的独立装配点
 * - 让旧 `DataProviderBootstrap` 可以在迁移期显式接入它
 */
object DataLayerBootstrap {
    private val initialized = AtomicBoolean(false)

    val apiLayer: CandleApiLayer by lazy { CandleApiLayer() }
    val snapshotManager: CandleSnapshotManager by lazy { CandleSnapshotManager(apiLayer) }
    val candleFacade: CandleDataFacade by lazy { CandleDataFacade(snapshotManager) }
    val projectionService: CandleProjectionService by lazy { CandleProjectionService() }
    val candleProvider: CandleDataProvider by lazy {
        CandleDataProvider(
            facade = candleFacade,
            projectionService = projectionService
        )
    }
    val candleSubscriptionService: CandleSubscriptionService by lazy {
        CandleSubscriptionService(candleProvider)
    }
    val syncLooper: SyncLooper by lazy {
        SyncLooper(
            snapshotManager = snapshotManager,
            candleProvider = candleProvider
        )
    }

    fun registerDataSyncListener(listener: suspend (Collection<model.dataprovider.CandleKey>) -> Unit) {
        syncLooper.registerListener(listener)
    }

    suspend fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        apiLayer.start()
        snapshotManager.initialize()
        syncLooper.start()
    }

    fun refreshDailyUniverse() {
        candleFacade.refreshDailyUniverse()
    }
}

/**
 * `SyncLooper` 只做节奏编排，不直接理解外部接口。
 *
 * 两条节奏分开：
 * - provider / listener drain 固定 1 秒，前端看到的版本推进稳定
 * - DAY `rt_k` 调度固定 1.2 秒，贴合 50/min 物理限额，避免每隔一秒被限流丢弃
 */
class SyncLooper(
    private val snapshotManager: CandleSnapshotManager,
    private val candleProvider: CandleDataProvider,
    private val intervalMs: Long = 1_000L,
    private val dayRealtimeIntervalMs: Long = 1_200L
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val listeners = CopyOnWriteArrayList<suspend (Collection<model.dataprovider.CandleKey>) -> Unit>()
    private var drainJob: Job? = null
    private var realtimeJob: Job? = null

    fun registerListener(listener: suspend (Collection<model.dataprovider.CandleKey>) -> Unit) {
        listeners += listener
    }

    fun start() {
        if (drainJob?.isActive == true || realtimeJob?.isActive == true) return
        realtimeJob = scope.launch {
            while (isActive) {
                snapshotManager.scheduleDayRealtimeUpdate()
                delay(dayRealtimeIntervalMs)
            }
        }
        drainJob = scope.launch {
            while (isActive) {
                val changedKeys = snapshotManager.drainChangedKeys()
                candleProvider.onDataSync(changedKeys)
                if (changedKeys.isNotEmpty()) {
                    listeners.forEach { listener ->
                        listener(changedKeys)
                    }
                }
                delay(intervalMs)
            }
        }
    }
}
