package ktor.module.llm.agent

import ktor.module.llm.Model
import ktor.module.llm.SiliconFlowModel
import ktor.module.llm.agent.abs.Agent
import ktor.module.llm.getJoinedCandles
import logger
import org.shiroumi.network.apis.LLMApi
import org.shiroumi.network.siliconFlow
import org.shiroumi.server.today

class OverviewAgent() : Agent<LLMApi>(siliconFlow()) {

    override val logger by logger("OverviewAgent")

    override val prompts: Prompts by lazy {
        Prompts(
            _sys = "prompt_overview_sys",
            _usr = "prompt_overview_usr"
        )
    }

    override val model: Model = SiliconFlowModel.DeepSeekV3Terminus

    suspend fun chat(tsCode: String) =
        chat(msg = Role.User provides "${prompts.usr}\n${getJoinedCandles(tsCode = tsCode)}\n今天的日期是$today")
}