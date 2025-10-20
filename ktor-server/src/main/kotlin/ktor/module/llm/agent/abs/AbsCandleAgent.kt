package ktor.module.llm.agent.abs

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import ktor.module.llm.getJoinedCandles
import org.shiroumi.network.apis.ChatCompletion
import org.shiroumi.network.apis.LLMApi
import org.shiroumi.network.siliconFlow

abstract class AbsCandleAgent() : Agent<LLMApi>(siliconFlow()) {

    abstract val suffixMsgs: List<String>

    private val json = Json { prettyPrint = true }

    suspend fun chat(tsCode: String, msgs: List<String> = listOf()): ChatCompletion? {
//        val msg = mutableListOf(prompts.usr, getJoinedCandles(tsCode = tsCode)).also { list ->
//            list.addAll(msgs)
//            list.addAll(suffixMsgs)
//        }
//        logger.warning("history: ${msg.joinToString("\n")}")
//        val res = chat(Role.User provides msg.joinToString("\n"))
//        logger.accept(json.encodeToString(res))
//        return res
        delay(5000L)
        return null
    }
}