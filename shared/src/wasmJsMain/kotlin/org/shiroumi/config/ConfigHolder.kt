package org.shiroumi.config

/**
 * JS 平台的 ConfigHolder 实现
 *
 * JS 是单线程的，不需要 ThreadLocal。
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
