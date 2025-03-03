package org.shiroumi.trading

abstract class Trader {

    /**
     * account, contains holding & balance
     * use this to buy or sell
     * update it every day after trading
     */
    val account = Account()

}