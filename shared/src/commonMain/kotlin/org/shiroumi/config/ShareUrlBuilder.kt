package org.shiroumi.config

/**
 * 分享链接构造器。
 *
 * 公开链接形态固定为 `${baseUrl}${optionalPort}/s/{token}`，
 * 其中 baseUrl 与可选端口完全派生自当前部署环境（编译期 AppEnvironment）：
 *
 * - debug:     http://<auto-lan>:9871/s/{token}
 * - debug-wan: http://bigsmart.ddns.net:9871/s/{token}
 * - release:   https://bigsmart.space/s/{token}
 *
 * 当 publicPort 为空（release 模式 publicPort: null 时）API_BASE_URL 不带端口后缀，
 * 此处也不应追加。判定方式是检查 API_BASE_URL 是否以 ":{PORT}" 结尾。
 */
object ShareUrlBuilder {

    private val needsPortSuffix: Boolean =
        AppEnvironment.API_BASE_URL.endsWith(":${AppEnvironment.PORT}")

    private val portSuffix: String =
        if (needsPortSuffix) ":${AppEnvironment.PORT}" else ""

    fun build(token: String): String = "${AppEnvironment.BASE_URL}$portSuffix/s/$token"
}
