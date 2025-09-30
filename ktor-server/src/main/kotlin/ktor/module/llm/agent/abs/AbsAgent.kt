package ktor.module.llm.agent.abs

import Logger
import ktor.module.llm.Model
import org.shiroumi.network.ApiDelegate
import org.shiroumi.network.apis.ChatCompletion
import org.shiroumi.network.apis.LLMApi
import org.shiroumi.network.apis.Message
import org.shiroumi.network.apis.chat
import org.shiroumi.server.rootDir
import java.io.File

abstract class Agent<T : LLMApi>(api: ApiDelegate<T>) {

    abstract val logger: Logger

    private val caller: LLMApi by api

    protected abstract val prompts: Prompts

    protected abstract val model: Model

    val history: History by lazy { History(prompts.sys) }

    suspend fun chat(msg: Message? = null): ChatCompletion {
        msg?.let { m -> history.remember(m) }
        return caller.chat(
            model = model.m,
            messages = history.content,
            tools = model.tools,
            temperature = model.temperature,
            topP = model.topP,
            topK = model.topK,
            jsonMode = model.jsonMode,
            enableThinking = model.enableThinking,
            thinkingBudget = model.thinkingBudget,
            maxTokens = model.maxTokens
        )
    }

    data class Prompts(
        private val _path: String = "$rootDir/assets",
        private val _sys: String? = null,
        private val _usr: String? = null
    ) {
        val sys: String? by lazy {
            File("$_path/$_sys").joinedContent
        }
        val usr: String? by lazy {
            File("$_path/$_usr").joinedContent
        }

        private val File.joinedContent: String?
            get() = if (exists()) readLines().joinToString("") else null

    }

    class History(
        private val sysPrompt: String? = null,
        private val ctxWindow: Int = 20
    ) {

        private var _content = sysPrompt?.let {
            mutableListOf(
                Role.System provides sysPrompt
            )
        } ?: mutableListOf()

        val content: List<Message> get() = _content

        fun remember(msg: Message) {
            _content.add(msg)
            if (_content.size > ctxWindow + 1) forgetOne()
        }

        private fun forgetOne() = _content.removeAt(1)
    }

    sealed class Role(val value: String) {
        data object System : Role("system")
        data object User : Role("user")
        data object Assistant : Role("assistant")
        data object Tool : Role("tool")

        infix fun provides(msg: String) = Message(role = value, content = msg)
    }

}