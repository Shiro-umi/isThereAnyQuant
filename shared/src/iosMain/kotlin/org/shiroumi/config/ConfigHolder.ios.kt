package org.shiroumi.config

/**
 * iOS 平台的 ConfigHolder 实现
 *
 * iOS 上 app 的配置访问发生在主线程驱动的 Compose 运行时内，是全局共享的单一实例，
 * 不需要线程隔离。语义与 JS actual 完全同构：前端拿到后端配置后写回此对象。
 */
actual object ConfigHolder {

    private var _config: QuantConfig = QuantConfig()

    /**
     * 获取当前全局配置
     */
    actual val config: QuantConfig
        get() = _config

    /**
     * 更新全局配置
     */
    actual fun update(newConfig: QuantConfig) {
        _config = newConfig
    }
}

/**
 * 快捷访问函数，方便调用
 */
actual fun config(): QuantConfig = ConfigHolder.config
