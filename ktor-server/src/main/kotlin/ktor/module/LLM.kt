package ktor.module

import org.shiroumi.network.ApiDelegate
import org.shiroumi.network.apis.LLMApi
import org.shiroumi.network.apis.Message
import org.shiroumi.network.apis.chat
import org.shiroumi.network.deepseek

abstract class AI<T>(
    api: ApiDelegate<T>,
    prompt: String? = null,
) {

    private val caller: T by api

    abstract var model: Model

    private val history: History by lazy {
        prompt?.let { History(prompt = prompt) } ?: History()
    }

    suspend fun chat(msg: String) {
        history.remember(Role.User provides msg)
        (caller as LLMApi).chat(model = model.value, messages = history.content)
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
        data object Tool: Role("tool")

        infix fun provides(msg: String) = Message(role = value, content = msg)
    }


    companion object DeepSeek : AI<LLMApi>(api = deepseek()) {

        override var model: Model = Model.Chat

        sealed class Model(val value: String) {
            data object Chat : Model("deepseek-chat")
            data object Reasoner : Model("deepseek-reasoner")
        }

    }
}

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