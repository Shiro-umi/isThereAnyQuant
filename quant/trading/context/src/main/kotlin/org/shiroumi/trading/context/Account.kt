package org.shiroumi.trading.context

class Account {

    private var _totalBalance: Double = 0.0
    val totalBalance: Double get() = _totalBalance

    private var _holdings: MutableList<StockHolding> = mutableListOf()
    val holdings: List<StockHolding> get() = _holdings

    val globalProfit: Double
        get() = totalBalance + holdings.sumOf { it.balance }

    // buy a stock, use {percent * totalBalance}
    fun buy(code: String, percent: Double) {
        val useBalance = totalBalance * percent
        if (holdings.any { it.code == code }) return
        if (useBalance > totalBalance) return
        _holdings.add(StockHolding(code = code, balance = useBalance.also { _totalBalance -= it }))
    }

    // sell a stock, add sellBalance to totalBalance
    fun sell(code: String) {
        val toSell = holdings.find { it.code == code }.also { _holdings.remove(it) } ?: return
        _totalBalance += toSell.balance
    }

    fun updateAll(updater: (StockHolding) -> StockHolding) {
        _holdings = holdings.map { holding -> updater(holding) }.toMutableList()
    }
}

data class StockHolding(
    val code: String = "",
    val balance: Double = 1.0,
    val profit: Double = 0.0
)

