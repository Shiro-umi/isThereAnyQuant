package org.shiroumi.quant_kmp.service

/**
 * 全局 WebSocket 连接状态。
 *
 * 由 [GlobalWebSocketClient] 单点维护并通过 `connectionStateFlow` 暴露给 ViewModel / UI。
 * 设计目标：让上层在订阅命令被默默推迟（断线期间 isRestorableStateCommand 丢弃）时
 * 仍能给用户提供合理的反馈，避免长时间无声的 loading 体验。
 */
enum class ConnectionState {
    /** 初始态或主动 disconnect 后。AuthGate 尚未触发 connect()。 */
    DISCONNECTED,

    /** connect() 协程进入连接尝试，等待握手成功。 */
    CONNECTING,

    /** WebSocket 握手成功，可正常收发。 */
    CONNECTED,

    /** 非主动断开，正在指数退避等待重连。订阅命令此时会被推迟到下次 CONNECTED。 */
    RECONNECTING
}
