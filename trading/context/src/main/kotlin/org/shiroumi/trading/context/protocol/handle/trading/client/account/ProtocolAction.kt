package org.shiroumi.trading.context.protocol.handle.trading.client.account

import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol
import trading.server.model.account_info
import trading.server.model.stock_holdings


/**
 * Actual protocol-action
 * @param protocol trading.client.account
 */
fun action(context: Context, protocol: trading.client.account): Protocol? {
    protocol.callback.params = account_info(holdings = listOf(stock_holdings("000001"), stock_holdings("000002")))
    return protocol.callback
}