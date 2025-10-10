package ktor.module.llm.agent

import ktor.module.llm.Model
import ktor.module.llm.SiliconFlowModel
import ktor.module.llm.agent.abs.Agent
import ktor.module.llm.getJoinedCandles
import logger
import org.shiroumi.network.apis.LLMApi
import org.shiroumi.network.siliconFlow
import org.shiroumi.server.today

class HighProbAreaAgent() : Agent<LLMApi>(siliconFlow()) {
    override val logger by logger("HighProbAreaAgent")

    override val prompts: Prompts by lazy {
        Prompts(
            _sys = "prompt_high_prob_sys",
            _usr = "prompt_high_prob_usr"
        )
    }

    override val model: Model = SiliconFlowModel.DeepSeekV3Terminus

    suspend fun chat(tsCode: String, overview: String) =
        chat(msg = Role.User provides "${prompts.usr}\n${getJoinedCandles(tsCode = tsCode)}\n$overview\n今天的日期是$today")
}