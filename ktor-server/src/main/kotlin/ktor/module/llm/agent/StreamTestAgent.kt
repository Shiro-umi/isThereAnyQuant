package ktor.module.llm.agent

import ktor.module.llm.Model
import ktor.module.llm.SiliconFlowModel
import ktor.module.llm.agent.abs.Agent
import ktor.module.llm.getJoinedCandles
import logger
import org.shiroumi.network.apis.LLMApi
import org.shiroumi.network.siliconFlow

class StreamTestAgent() : Agent<LLMApi>(siliconFlow()) {

    override val logger by logger("StreamTestAgent")

    override val prompts: Prompts by lazy {
        Prompts()
    }

    override val model: Model = SiliconFlowModel.DeepSeekV3Terminus

    suspend fun chat() = chat(msg = Role.User provides "介绍一下1994年发生过什么")

    suspend fun chatStream() = chatStream(msg = Role.User provides "介绍一下1994年发生过什么")
}