package org.shiroumi.trading.strategy

abstract class KtsStrategy(
    private val fileName: String,
    private val port: Int,
) : AbsStrategy {

    override val script: Script = Script(
        type = ScriptType.Kts,
        fileName = fileName,
        port = port
    )

    override fun loadScript() {
        // todo compile kts script
    }
}