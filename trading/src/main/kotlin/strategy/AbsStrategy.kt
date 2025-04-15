package strategy

import org.shiroumi.configs.BuildConfigs

interface AbsStrategy {

    val script: Script // side-load-strategy-script

    /**
     * load your side-load quant-script here
     * it will have different implementation for different languages
     */
    fun loadScript()
}

/**
 * definition of side-load-script
 */
data class Script(
    val type: ScriptType,
    val fileName: String, // where to save or compile the side-load-script
    val port: Int // server communicate with script on port
) {
    val path = "$scriptDir/$fileName"

    companion object {
        const val scriptDir = BuildConfigs.SCRIPT_BASE_DIR
    }
}

/**
 * enumeration of all supported script type
 */
sealed class ScriptType {
    object Kts : ScriptType()
    object Py : ScriptType()
}