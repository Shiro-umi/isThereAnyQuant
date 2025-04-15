package org.shiroumi.trading.context.protocol.protocol_handle.status.server.error

import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol

/**
 * Actual protocol-action
 * @param protocol status.server.error
 */
suspend fun action(context: Context, protocol: status.server.error): Protocol? {
    println("received: status.server.error")
    return null
}