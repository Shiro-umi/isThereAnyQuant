package org.shiroumi.config

/**
 * 全局配置持有者
 *
 * 适用于所有平台（Android, JVM, JS）。
 * 后端启动时会通过文件加载并更新此对象；
 * 前端可以通过 API 获取后端配置后更新此对象。
 */
expect object ConfigHolder {

    /**
     * 获取当前全局配置
     */
    val config: QuantConfig

    /**
     * 更新全局配置
     */
    fun update(newConfig: QuantConfig)
}

/**
 * 快捷访问函数，方便调用
 */
expect fun config(): QuantConfig
