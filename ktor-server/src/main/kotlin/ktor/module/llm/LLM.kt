package ktor.module.llm

import kotlinx.serialization.json.JsonArray
import org.shiroumi.database.functioncalling.getJoinedCandles
import org.shiroumi.ksp.Description
import org.shiroumi.ksp.FunctionCall


sealed class DeepSeekModel(m: String) : Model(m) {
    data object Chat : DeepSeekModel("deepseek-chat")
    data object Reasoner : DeepSeekModel("deepseek-reasoner")
}

sealed class SiliconFlowModel(
    m: String,
    temperature: Float,
    topP: Float,
    topK: Int,
    enableThinking: Boolean,
    thinkingBudget: Int,
    maxTokens: Int,
    jsonMode: Boolean
) : Model(m, temperature, topP, topK, enableThinking, thinkingBudget, maxTokens, jsonMode) {
    data object DeepSeekV3Terminus : SiliconFlowModel(
        m = "deepseek-ai/DeepSeek-V3.1-Terminus",
        temperature = .35f,
        topP = 0.9f,
        topK = 100,
        enableThinking = true,
        thinkingBudget = 163839,
        maxTokens = 163839,
        jsonMode = false
    )
}

open class Model(
    val m: String,
    val temperature: Float = 0.9f,
    val topP: Float = 0.9f,
    val topK: Int = 50,
    val enableThinking: Boolean = false,
    val thinkingBudget: Int = 4096,
    val maxTokens: Int = 4096,
    val jsonMode: Boolean = false,
    val tools: JsonArray? = null
)

//@FunctionCall(description = "记录工作清单")
//suspend fun writeNote(
//    @Description("工作清单内容，必须是全量更新") content: String
//): String {
//    println(
//        """
//记录工作清单
//$content
//    """.trimIndent()
//    )
//    val f = File("$rootDir/memory")
//    if (f.exists()) f.delete()
//    f.createNewFile()
//    f.writeText(content)
//    return "$content，现在执行下一项工作"
//}

//@FunctionCall(description = "获取交易策略的相关知识")
//suspend fun getKnowledge(
//    @Description("想要查询的内容，在[基本概念,基本图形,通道交易模式,三推交易模式,交易信号]重选择") knowledgeName: String
//) = File("$rootDir/$knowledgeName").readLines().joinToString("")

@FunctionCall(description = "获取最近30天的股票日线")
suspend fun getJoinedCandles(
    @Description("股票代码") tsCode: String
) = getJoinedCandles(tsCode, 60).toString()

