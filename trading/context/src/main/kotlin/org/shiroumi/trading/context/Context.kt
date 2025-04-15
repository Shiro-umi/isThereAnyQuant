package org.shiroumi.trading.context

import org.shiroumi.trading.context.socket.ProtocolSocketManager
import org.shiroumi.trading.context.stepiterator.TradingDateIterator

class Context {

    /**
     * account, contains holding & balance
     * use this to buy or sell
     * update it every day after trading
     */
    val account = Account()

    /**
     * a socket manager to communicate with client by protocol
     */
    val socketManager = ProtocolSocketManager(context = this)

    /**
     * trading date iterator
     * 2d iterator, tradingDate[[ actions ]]
     */
    val iterator: TradingDateIterator = TradingDateIterator()
}