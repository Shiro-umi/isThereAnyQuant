package ktor.module.llm.agent

import ktor.module.llm.Model
import ktor.module.llm.SiliconFlowModel
import ktor.module.llm.agent.abs.AbsCandleAgent
import org.shiroumi.server.today
import utils.logger

class HighProbAreaAgent() : AbsCandleAgent() {
    override val logger by logger("HighProbAreaAgent")

    override val suffixMsgs: List<String> = listOf("今天的日期是$today")

    override val prompts: Prompts by lazy {
        Prompts(
            _sys = "prompt_high_prob_sys",
            _usr = "prompt_high_prob_usr"
        )
    }

    override val model: Model = SiliconFlowModel.DeepSeekV3Exp
}