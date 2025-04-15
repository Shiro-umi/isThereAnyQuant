package org.shiroumi.trading.context.protocol.protocol_handle.trading.server.do_at_9_25

import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol


/**
 * Actual protocol-action
 * @param protocol trading.client.buy
 */
fun action(context: Context, protocol: trading.server.do_at_9_25): Protocol {
    println("action received protocol: $protocol")
    return protocol
}