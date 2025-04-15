package org.shiroumi.trading.context.protocol.protocol_handle.trading.client.buy

import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol
import trading.client.buy


/**
 * Actual protocol-action
 * @param protocol trading.client.buy
 */
fun action(context: Context, protocol: buy): Protocol? {
    println("action received protocol: $protocol")
    return protocol
}