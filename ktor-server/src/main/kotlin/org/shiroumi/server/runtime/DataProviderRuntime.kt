package org.shiroumi.server.runtime

/**
 * DataProvider 运行时入口封装。
 *
 * 这个类的作用是把“阶段服务”和“阶段协调器”组合成一个可启动的整体，
 * 避免未来在 `Main.kt` 或 DI 装配时散落多个启动调用。
 */
class DataProviderRuntime(
    private val executionPhaseService: ExecutionPhaseService,
    private val coordinator: DataProviderRuntimeCoordinator
) {
    @Volatile
    private var started: Boolean = false

    /**
     * 幂等启动运行时。
     * 重复调用不会重复启动内部定时器和订阅逻辑。
     */
    fun start() {
        if (started) return
        started = true
        executionPhaseService.start()
        coordinator.start()
    }
}
