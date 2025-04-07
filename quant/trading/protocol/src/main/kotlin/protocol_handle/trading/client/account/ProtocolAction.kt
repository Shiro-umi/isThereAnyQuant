package protocol_handle.trading.client.account

import protocol.model.Protocol
import trading.server.model.account_info
import trading.server.model.stock_holdings


/**
 * Actual protocol-action
 * @param protocol trading.client.account
 */
fun action(protocol: trading.client.account): Protocol? {
    println("action received protocol: $protocol")
    protocol.callback.params = account_info(holdings = listOf(stock_holdings("000001"), stock_holdings("000002")))
    return protocol.callback
}