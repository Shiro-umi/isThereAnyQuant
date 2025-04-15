package org.shiroumi.trading.context.protocol.protocol_handle.status.server.exit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol
import socket_main
import threadLocalJob

/**
 * Actual protocol-action
 * @param protocol status.server.exit
 */
fun action(context: Context, protocol: status.server.exit): Protocol? {
    println("received: status.server.exit")
    runBlocking(Dispatchers.socket_main) {
        threadLocalJob.get().cancel()
    }
    return protocol
}