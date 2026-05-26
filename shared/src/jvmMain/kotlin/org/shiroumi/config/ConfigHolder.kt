package org.shiroumi.config

/**
 * JVM 平台的 ConfigHolder 实现（非 ThreadLocal）
 *
 * 服务器端配置是全局共享的，不需要线程隔离。
 * 这个实现覆盖 commonMain 中的 ThreadLocal 版本。
 */
actual object ConfigHolder {

    @Volatile
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
