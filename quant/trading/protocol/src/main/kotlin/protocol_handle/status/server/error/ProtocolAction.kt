package protocol_handle.status.server.error

import org.shiroumi.trading.schedule.threadLocalSchedular
import protocol.model.Protocol

/**
 * Actual protocol-action
 * @param protocol status.server.error
 */
suspend fun action(protocol: status.server.error): Protocol? {
    println("received: status.server.error")
    return null
}