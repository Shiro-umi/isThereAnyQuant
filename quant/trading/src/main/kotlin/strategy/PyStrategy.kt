package strategy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.shiroumi.configs.BuildConfigs

class PyStrategy(
    private val fileName: String,
    private val port: Int,
) : AbsStrategy {

    override val script: Script = Script(
        type = ScriptType.Py,
        fileName = fileName,
        port = port
    )

    /**
     * load py script
     * then start up the py-socket client
     */
    override fun loadScript(): Unit = runBlocking {
        supervisorScope {
            launch(Dispatchers.IO) {
                val process = Runtime.getRuntime()
                    .exec("python3 ${BuildConfigs.SCRIPT_BASE_CLIENT_DIR} ${script.port} ${script.path}")
                println("script client started as pid: ${process.pid()}")
            }
        }
    }
}