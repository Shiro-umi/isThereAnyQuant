package protocol_handle.trading.client.account

import protocol.model.Protocol


/**
 * Actual protocol-action
 * @param protocol trading.client.account
 */
fun action(protocol: trading.client.account): Protocol? {
    return protocol.callback
}