package org.shiroumi.trading.strategy

interface AbsStrategy {

    val script: Script // side-load-strategy-script

    /**
     * load your side-load quant-script here
     * it will have different implementation for different languages
     */
    fun loadScript(fileName: String)
}

/**
 * definition of side-load-script
 */
data class Script(
    val type: ScriptType,
    val path: String, // where to save or compile the side-load-script
    val port: Int // server communicate with script on port
)

/**
 * enumeration of all supported script type
 */
sealed class ScriptType {
    object Kts : ScriptType()
}