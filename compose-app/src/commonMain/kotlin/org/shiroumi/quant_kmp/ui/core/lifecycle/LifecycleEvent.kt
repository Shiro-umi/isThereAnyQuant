package org.shiroumi.quant_kmp.ui.core.lifecycle

/**
 * 应用生命周期事件
 */
enum class LifecycleEvent {
    /**
     * 应用进入前台
     */
    ON_RESUME,
    
    /**
     * 应用离开前台
     */
    ON_PAUSE,
    
    /**
     * 应用开始（首次启动）
     */
    ON_START,
    
    /**
     * 应用停止（即将销毁）
     */
    ON_STOP
}

/**
 * 生命周期观察者接口
 */
interface LifecycleObserver {
    fun onLifecycleEvent(event: LifecycleEvent)
}

/**
 * 生命周期管理器
 */
object LifecycleManager {
    private val observers = mutableListOf<LifecycleObserver>()
    
    /**
     * 注册生命周期观察者
     */
    fun registerObserver(observer: LifecycleObserver) {
        if (!observers.contains(observer)) {
            observers.add(observer)
        }
    }
    
    /**
     * 注销生命周期观察者
     */
    fun unregisterObserver(observer: LifecycleObserver) {
        observers.remove(observer)
    }
    
    /**
     * 分发生命周期事件
     */
    fun dispatchEvent(event: LifecycleEvent) {
        observers.forEach { it.onLifecycleEvent(event) }
    }
}
