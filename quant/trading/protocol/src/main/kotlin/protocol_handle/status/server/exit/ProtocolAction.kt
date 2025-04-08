package protocol_handle.status.server.exit

import org.shiroumi.trading.schedule.threadLocalSchedular
import protocol.model.Protocol

/**
 * Actual protocol-action
 * @param protocol status.server.exit
 */
suspend fun action(protocol: status.server.exit): Protocol? {
    println("received: status.server.exit")
    return null
}