package org.shiroumi.server.dataprovider.bootstrap

import org.shiroumi.server.dataprovider.adapter.AuthoritativeRealtimeDailyCandleLoader
import org.shiroumi.server.data.bootstrap.DataLayerBootstrap
import org.shiroumi.server.data.facade.CandleDataFacade
import org.shiroumi.server.dataprovider.registry.DataProviderRegistry
import org.shiroumi.server.dataprovider.service.DataProviderReadService
import org.shiroumi.server.dataprovider.service.DataProviderUpdateService
import org.shiroumi.server.runtime.DataProviderRuntime
import org.shiroumi.server.runtime.DataProviderRuntimeCoordinator
import org.shiroumi.server.runtime.ExecutionPhaseService
import org.shiroumi.server.runtime.lifecycle.ServerLifecycleManager
import org.shiroumi.server.runtime.lifecycle.ServerLifecyclePhase
import org.shiroumi.server.runtime.lifecycle.SimpleLifecycleTask
import org.shiroumi.server.runtime.market.MarketStatusProjectionService
import org.shiroumi.server.runtime.stock.StockCatalogSnapshotService
import org.shiroumi.server.runtime.stock.StockListContextRuntimeService
import org.shiroumi.server.subscription.intraday.IntradaySnapshotSubscriptionService
import org.shiroumi.server.runtime.strategy.StrategyPositionHolder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 新 DataProvider 架构的统一装配入口。
 *
 * 目标：
 * - 把新 runtime、provider factory、read/update service 的创建逻辑集中在一处
 * - 确保 `Application.module` 能以一个明确入口启动新架构
 * - 避免后续在路由或 service 中散落 new 对象的装配代码
 *
 * service-first 模式下，本 bootstrap 只装配数据层链路：
 * - 不持有任何策略 runtime（盘中情绪/因子/组合/持仓投影全在 strategy-service）
 * - 三个 SERVICE_OWNED_TOPICS 订阅服务直接通过 `StrategyRuntimeBridge` 消费 service snapshot
 */
object DataProviderBootstrap {
    private val initialized = AtomicBoolean(false)

    val registry: DataProviderRegistry by lazy { DataProviderRegistry() }
    val executionPhaseService: ExecutionPhaseService by lazy { ExecutionPhaseService() }
    val runtimeCoordinator: DataProviderRuntimeCoordinator by lazy {
        DataProviderRuntimeCoordinator(
            executionPhaseService = executionPhaseService,
            registry = registry
        )
    }
    val runtime: DataProviderRuntime by lazy {
        DataProviderRuntime(
            executionPhaseService = executionPhaseService,
            coordinator = runtimeCoordinator
        )
    }

    val authoritativeRealtimeDailyCandleLoader by lazy { AuthoritativeRealtimeDailyCandleLoader() }

    val readService: DataProviderReadService by lazy { DataProviderReadService(registry) }
    val updateService: DataProviderUpdateService by lazy { DataProviderUpdateService(registry) }
    val candleFacade: CandleDataFacade by lazy { DataLayerBootstrap.candleFacade }
    val candleProjectionService by lazy { DataLayerBootstrap.projectionService }
    val stockCatalogSnapshotService: StockCatalogSnapshotService by lazy { StockCatalogSnapshotService() }

    val strategyPositionTrackingSubscriptionService:
        org.shiroumi.server.subscription.strategy.StrategyPositionTrackingSubscriptionService by lazy {
        org.shiroumi.server.subscription.strategy.StrategyPositionTrackingSubscriptionService()
    }
    val strategyPositionSubscriptionService:
        org.shiroumi.server.subscription.strategy.StrategyPositionSubscriptionService by lazy {
        org.shiroumi.server.subscription.strategy.StrategyPositionSubscriptionService(
            positionHolder = StrategyPositionHolder
        )
    }

    val candleSubscriptionService by lazy { DataLayerBootstrap.candleSubscriptionService }
    val intradaySnapshotSubscriptionService: IntradaySnapshotSubscriptionService by lazy {
        IntradaySnapshotSubscriptionService()
    }
    val marketStatusProjectionService: MarketStatusProjectionService by lazy {
        MarketStatusProjectionService(
            executionPhaseService = executionPhaseService
        )
    }
    val stockListContextRuntimeService: StockListContextRuntimeService by lazy {
        StockListContextRuntimeService()
    }

    /**
     * 通用型 server 生命周期管理器。
     *
     * 关键基础设施启动是 critical，保证 READY 关键路径不再绑定情绪/盘中因子重型 warmup。
     */
    val serverLifecycleManager: ServerLifecycleManager by lazy {
        ServerLifecycleManager(
            tasks = listOf(
                SimpleLifecycleTask(
                    name = "start-data-provider-runtime",
                    critical = true,
                    phase = ServerLifecyclePhase.BOOTSTRAPPING
                ) {
                    runtime.start()
                },
                SimpleLifecycleTask(
                    name = "start-market-status-projection",
                    critical = true,
                    phase = ServerLifecyclePhase.BOOTSTRAPPING
                ) {
                    marketStatusProjectionService.start()
                },
                SimpleLifecycleTask(
                    name = "start-stock-list-context-runtime",
                    critical = true,
                    phase = ServerLifecyclePhase.BOOTSTRAPPING
                ) {
                    stockListContextRuntimeService.initialize()
                },
                SimpleLifecycleTask(
                    name = "warmup-stock-catalog-snapshots",
                    critical = true,
                    phase = ServerLifecyclePhase.WARMING_UP
                ) {
                    stockCatalogSnapshotService.initialize()
                },
                SimpleLifecycleTask(
                    name = "warmup-strategy-positions",
                    critical = true,
                    phase = ServerLifecyclePhase.WARMING_UP
                ) {
                    StrategyPositionHolder.initialize()
                    val snapshot = StrategyPositionHolder.snapshot.value
                    println(
                        "[StrategyPositions] warmup done: " +
                            if (snapshot != null) {
                                "tradeDate=${snapshot.tradeDate}, positions=${snapshot.currentPositions.size}, " +
                                    "nextSelections=${snapshot.nextSessionSelections.size}, newlySelected=${snapshot.newlySelected.size}"
                            } else {
                                "snapshot is null"
                            }
                    )
                },
                SimpleLifecycleTask(
                    name = "warmup-data-layer-candle-snapshots",
                    critical = true,
                    phase = ServerLifecyclePhase.WARMING_UP
                ) {
                    DataLayerBootstrap.initialize()
                }
            )
        )
    }

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        serverLifecycleManager.start()
    }
}
