package ktor.module.llm.agent

import ktor.module.llm.Model
import ktor.module.llm.SiliconFlowModel
import ktor.module.llm.agent.abs.AbsCandleAgent
import org.shiroumi.network.apis.ChatCompletion
import org.shiroumi.server.today
import utils.logger

class OverviewAgent : AbsCandleAgent() {

    override val logger by logger("OverviewAgent")

    override val suffixMsgs: List<String> by lazy {
        listOf(
//            "粗略高低点：${runBlocking { extremePoints(tsCode) }}",
            "今天的日期是$today"
        )
    }

    private var tsCode: String = ""

    override val prompts: Prompts by lazy {
        Prompts(
            _sys = "prompt_overview_sys",
            _usr = "prompt_overview_usr"
        )
    }

    override val model: Model = SiliconFlowModel.DeepSeekV3Exp

    override suspend fun chat(tsCode: String, msgs: List<String>): ChatCompletion {
        this.tsCode = tsCode
        return super.chat(tsCode, msgs)
    }
}