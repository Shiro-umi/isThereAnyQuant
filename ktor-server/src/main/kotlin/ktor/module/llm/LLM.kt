package ktor.module.llm

import kotlinx.serialization.json.JsonArray
import org.shiroumi.ai.function.llmTools


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
    tools: JsonArray? = null,
    jsonMode: Boolean
) : Model(m, temperature, topP, topK, enableThinking, thinkingBudget, maxTokens, jsonMode, tools) {

    data object DeepSeekV3T : SiliconFlowModel(
        m = "deepseek-ai/DeepSeek-V3.1-Terminus",
        temperature = .35f,
        topP = 0.35f,
        topK = 100,
        enableThinking = false,
        thinkingBudget = 163839,
        maxTokens = 163839,
        jsonMode = false
    )

    data object DeepSeekV3TTool : SiliconFlowModel(
        m = "deepseek-ai/DeepSeek-V3.1-Terminus",
        temperature = .35f,
        topP = 0.35f,
        topK = 100,
        tools = llmTools,
        enableThinking = false,
        thinkingBudget = 163839,
        maxTokens = 163839,
        jsonMode = false
    )

    data object DeepSeekV3Exp : SiliconFlowModel(
        m = "deepseek-ai/DeepSeek-V3.2-Exp",
        temperature = .35f,
        topP = 0.4f,
        topK = 30,
        enableThinking = true,
        thinkingBudget = 1024,
        maxTokens = 1024,
        jsonMode = false
    )

    data object DeepSeekV3ExpTool : SiliconFlowModel(
        m = "deepseek-ai/DeepSeek-V3.2-Exp",
        temperature = .35f,
        topP = 0.4f,
        topK = 30,
        tools = llmTools,
        enableThinking = false,
        thinkingBudget = 1024,
        maxTokens = 10240,
        jsonMode = false
    )

    data object Ring1T : SiliconFlowModel(
        m = "inclusionAI/Ring-1T",
        temperature = 0.35f,
        topP = 0.4f,
        topK = 20,
        enableThinking = true,
        thinkingBudget = 1024,
        maxTokens = 2048,
        jsonMode = false
    )

    data object Ring1TTool : SiliconFlowModel(
        m = "inclusionAI/Ring-1T",
        temperature = 0.35f,
        topP = 0.4f,
        topK = 20,
        tools = llmTools,
        enableThinking = true,
        thinkingBudget = 1024,
        maxTokens = 2048,
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