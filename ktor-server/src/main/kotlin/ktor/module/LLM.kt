package ktor.module

import Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import logger
import org.shiroumi.ai.function.functionCall
import org.shiroumi.network.ApiDelegate
import org.shiroumi.network.apis.LLMApi
import org.shiroumi.network.apis.Message
import org.shiroumi.network.apis.chat
import org.shiroumi.network.deepseek
import org.shiroumi.network.siliconFlow

abstract class AI<T>(
    api: ApiDelegate<T>,
    prompt: String? = null,
) {

    abstract val logger: Logger

    private val caller: T by api

    abstract var model: Model

    private val history: History by lazy {
        prompt?.let { History(prompt = prompt) } ?: History()
    }

    suspend fun chat(msg: String, onDone: ((res: String) -> Unit)? = null) {
        history.remember(Role.User provides msg)
        val res = (caller as LLMApi).chat(
            model = model.m,
            messages = history.content,
            temperature = model.temperature,
            topK = model.topK
        ).choices.first().message
        // 模型调用
        res.toolCalls?.firstOrNull()?.let { toolCall ->
            val f = toolCall.function
            val toolRes = functionCall(name = f.name, params = Json.parseToJsonElement(f.arguments) as JsonObject)
            history.remember((Role.Tool provides "$toolRes").also { it.toolCallId = toolCall.id })
            chat(msg)
        } ?: (onDone?.invoke(res.content) ?: logger.notify("final result: ${res.content}"))
    }

    /**
     * 定义消息角色。
     *
     * @property value 角色名称。
     */
    sealed class Role(val value: String) {
        data object System : Role("system")
        data object User : Role("user")
        data object Assistant : Role("assistant")
        data object Tool : Role("tool")

        infix fun provides(msg: String) = Message(role = value, content = msg)
    }
}

class DeepSeek : AI<LLMApi>(api = deepseek()) {

    override val logger by logger("DeepSeek")

    override var model: Model = DeepSeekModel.Chat

    sealed class DeepSeekModel(m: String) : Model(m) {
        data object Chat : DeepSeekModel("deepseek-chat")
        data object Reasoner : DeepSeekModel("deepseek-reasoner")
    }
}

class SiliconFlow(
    prompt: String,
    model: SiliconFlowModel = SiliconFlowModel.DeepSeekR1
) : AI<LLMApi>(api = siliconFlow(), prompt = prompt) {

    override val logger by logger("SiliconFlow")

    override var model: Model = model

    sealed class SiliconFlowModel(m: String, temperature: Float, topK: Int) : Model(m, temperature, topK) {
        data object DeepSeekR1 : SiliconFlowModel(
            m = "deepseek-ai/DeepSeek-R1",
            temperature = 0.5f,
            topK = 30,
        )

        data object KimiK2 : SiliconFlowModel(
            m = "moonshotai/Kimi-K2-Instruct-0905",
            temperature = 0.5f,
            topK = 30
        )

        data object QianWen3Coder480B : SiliconFlowModel(
            m = "Qwen/Qwen3-Coder-480B-A35B-Instruct",
            temperature = 0.5f,
            topK = 30
        )
    }
}

open class Model(
    val m: String,
    val temperature: Float = 1f,
    val topK: Int = 50
)

private class History(
    private val prompt: String = "",
    private val ctxWindow: Int = 10
) {

    private var _content = mutableListOf(AI.Role.System provides prompt)
    val content: List<Message> get() = _content

    fun remember(msg: Message) {
        _content.add(msg)
        if (_content.size > ctxWindow + 1) forgetOne()
    }

    private fun forgetOne() = _content.removeAt(1)

    fun clear() {
        _content = mutableListOf(AI.Role.System provides prompt)
    }
}