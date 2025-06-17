package org.shiroumi.trading.context.protocol.handle.trading.client.buy

import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol
import trading.client.buy


/**
 * Actual protocol-action
 * @param protocol trading.client.buy
 */
fun action(context: Context, protocol: buy): Protocol? {
    return protocol
}