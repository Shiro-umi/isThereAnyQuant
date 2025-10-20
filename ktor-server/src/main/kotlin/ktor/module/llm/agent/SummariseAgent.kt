package ktor.module.llm.agent

import ktor.module.llm.Model
import ktor.module.llm.SiliconFlowModel
import ktor.module.llm.agent.abs.AbsCandleAgent
import org.shiroumi.server.today
import utils.logger

class SummariseAgent() : AbsCandleAgent() {

    override val logger by logger("SummariseAgent")

    override val suffixMsgs: List<String> = listOf("今天的日期是$today")

    override val prompts: Prompts by lazy {
        Prompts(
            _sys = "prompt_summarise_sys",
            _usr = "prompt_summarise_usr"
        )
    }

    override val model: Model = SiliconFlowModel.DeepSeekV3Exp
}