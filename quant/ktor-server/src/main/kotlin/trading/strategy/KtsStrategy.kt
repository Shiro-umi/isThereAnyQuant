package org.shiroumi.trading.strategy

abstract class KtsStrategy(
    private val path: String,
    private val port: Int,
) : AbsStrategy {

    override val script: Script = Script(
        type = ScriptType.Kts,
        path = path,
        port = port
    )

    override fun loadScript(fileName: String) {
        // todo compile kts script
    }
}