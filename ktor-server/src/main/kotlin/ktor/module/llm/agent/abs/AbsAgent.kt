package ktor.module.llm.agent.abs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import ktor.module.llm.Model
import okhttp3.ResponseBody
import org.shiroumi.ai.function.functionCall
import org.shiroumi.network.ApiDelegate
import org.shiroumi.network.apis.*
import org.shiroumi.server.rootDir
import utils.Logger
import java.io.File

abstract class Agent<T : LLMApi>(api: ApiDelegate<T>, historyProvider: () -> History = { History() }) {

    abstract val logger: Logger

    private val caller: LLMApi by api

    protected abstract val prompts: Prompts

    protected abstract val model: Model

    val history: History = historyProvider()

    open suspend fun chat(msg: Message? = null): ChatCompletion {
        logger.warning("completion started.")
        val start = System.currentTimeMillis()
        msg?.let { m -> history.remember(m) }

        var res = caller.chat(
            model = model.m,
            messages = prompts.list.apply {
                addAll(history.content)
            },
            tools = model.tools,
            temperature = model.temperature,
            topP = model.topP,
            topK = model.topK,
            jsonMode = model.jsonMode,
            enableThinking = model.enableThinking,
            thinkingBudget = model.thinkingBudget,
            maxTokens = model.maxTokens,
        )
        logger.accept(res.choices[0].message.content)
        if (msg?.oneUse == true) history.forget(msg)
        history.remember(res.choices[0].message)
        res.choices[0].message.toolCalls?.run {
            forEach { t ->
                val f = t.function
                logger.notify("function call: ${f.name}, args: ${f.arguments}")
                val toolRes = functionCall(f.name, Json.parseToJsonElement(f.arguments).jsonObject)
                val toolMsg = Role.Tool provides "$toolRes"
                toolMsg.toolCallId = t.id
                history.remember(toolMsg)
            }
            res = chat()
        }

        logger.warning("completion end. cost: ${(System.currentTimeMillis() - start) / 1000f}s")
        return res
    }

    suspend fun chatStream(msg: Message? = null): ResponseBody {
        logger.warning("stream completion started.")
        val start = System.currentTimeMillis()
        msg?.let { m -> history.remember(m) }
        val res = caller.chatStream(
            model = model.m,
            messages = history.content,
            tools = model.tools,
            temperature = model.temperature,
            topP = model.topP,
            topK = model.topK,
            jsonMode = model.jsonMode,
            enableThinking = model.enableThinking,
            thinkingBudget = model.thinkingBudget,
            maxTokens = model.maxTokens,
        )
        logger.warning("stream completion end. cost: ${(System.currentTimeMillis() - start) / 1000f}s")
        return res
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

        val list: MutableList<Message> by lazy {
            mutableListOf(
                Role.System provides (sys ?: ""),
                Role.User provides (usr ?: "")
            )
        }
    }

    class History(
//        private val sysPrompt: String? = null,
        private val ctxWindow: Int = 20
    ) {

        private var _content = mutableListOf<Message>()
//        private var _content = sysPrompt?.let {
//            mutableListOf(
//                Role.System provides sysPrompt
//            )
//        } ?: mutableListOf()

        val content: List<Message> get() = _content

        fun remember(msg: Message) {
            _content.add(msg)
            if (_content.size > ctxWindow + 1) forgetOne()
        }

        fun forget(msg: Message) = _content.remove(msg)

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