package ktor.module

import io.ktor.server.application.*
import io.ktor.server.plugins.forwardedheaders.*

/**
 * 配置 Cloudflare Tunnel 必需的 Forwarded Headers 插件
 *
 * Cloudflare Tunnel 通过 cloudflared 将流量转发到本地服务器时，
 * 会设置 X-Forwarded-* 和 Forwarded headers。
 * 这些插件确保 Ktor 能正确识别：
 * - 客户端真实 IP（X-Forwarded-For）
 * - 原始请求协议（X-Forwarded-Proto，Cloudflare 始终为 https）
 * - 原始请求域名（X-Forwarded-Host）
 *
 * 必须在 configureSecurity() 之前调用，确保 CORS / JWT 等模块能获取正确的 origin 信息。
 */
fun Application.configureForwardedHeaders() {
    install(XForwardedHeaders) {
        // 信任所有代理（Cloudflare Tunnel 的内网代理地址不固定）
        // 如后续有更高安全需求，可在此限制为 Cloudflare 的 IP 段
    }
    install(ForwardedHeaders) {
        // 信任所有代理
    }
}
