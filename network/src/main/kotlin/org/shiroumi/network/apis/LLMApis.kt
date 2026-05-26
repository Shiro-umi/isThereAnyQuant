package org.shiroumi.network.apis

import io.ktor.client.statement.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.shiroumi.network.ApiClient
import org.shiroumi.network.deepseek
import org.shiroumi.network.siliconFlow

@Serializable
data class ChatCompletionUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)

@Serializable
data class ToolCalls(
    val id: String,
    val type: String,
    val function: FunctionTool,
)

@Serializable
data class FunctionTool(
    val name: String,
    val arguments: String
)

@Serializable
data class Message(
    val role: String,
    val content: String,
    @SerialName("tool_calls") val toolCalls: List<ToolCalls>? = null,
    @SerialName("tool_call_id") var toolCallId: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    var oneUse: Boolean? = false
)

@Serializable
data class Choice(
    val index: Int,
    val message: Message,
    val logprobs: String? = "",
    @SerialName("finish_reason") val finishReason: String
)

@Serializable
data class ChatCompletion(
    val id: String,
    @SerialName("object") val objectStr: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: ChatCompletionUsage,
    @SerialName("system_fingerprint") val systemFingerprint: String
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val objectStr: String,
    val created: Long,
    val model: String,
    val choices: List<ChatChunkChoice>,
    val usage: ChatCompletionUsage? = null,
    @SerialName("system_fingerprint") val systemFingerprint: String? = null
)

@Serializable
data class ChatChunkChoice(
    val index: Int,
    val delta: ChatChunkDelta,
    val logprobs: JsonElement? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChatChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallChunk>? = null
)

@Serializable
data class ToolCallChunk(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallChunk? = null
)

@Serializable
data class FunctionCallChunk(
    val name: String? = null,
    val arguments: String? = null
)

/**
 * LLM API 请求体
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = false,
    val temperature: Float = 0.9f,
    @SerialName("top_p") val topP: Float = 0.9f,
    @SerialName("top_k") val topK: Int = 50,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    @SerialName("enable_thinking") val enableThinking: Boolean? = null,
    @SerialName("thinking_budget") val thinkingBudget: Int? = null,
    @SerialName("response_format") val responseFormat: JsonObject? = null,
    val tools: JsonArray? = null
)

/**
 * 全局 API 实例
 */
val deepseek: LLMApi by deepseek()

val siliconFlow: LLMApi by siliconFlow()

/**
 * LLM API 接口
 */
interface LLMApi {
    suspend fun post(body: ChatCompletionRequest): ChatCompletion
    suspend fun postStream(body: ChatCompletionRequest): HttpResponse
}

/**
 * LLM API 实现
 */
class LLMApiImpl(private val client: ApiClient) : LLMApi {
    override suspend fun post(body: ChatCompletionRequest): ChatCompletion {
        return client.post("/v1/chat/completions", body)
    }

    override suspend fun postStream(body: ChatCompletionRequest): HttpResponse {
        return client.postStream("/v1/chat/completions", body)
    }
}

private val pretty = Json { prettyPrint = true }

suspend fun LLMApi.chat(
    model: String,
    messages: List<Message>,
    tools: JsonArray? = null,
    temperature: Float = 0.9f,
    topP: Float = 0.9f,
    topK: Int = 50,
    jsonMode: Boolean = false,
    enableThinking: Boolean = true,
    thinkingBudget: Int = 4096,
    maxTokens: Int = 4096
) = post(
    ChatCompletionRequest(
        model = model,
        messages = messages,
        stream = false,
        temperature = temperature,
        topP = topP,
        topK = topK,
        maxTokens = maxTokens,
        enableThinking = if (enableThinking) true else null,
        thinkingBudget = if (enableThinking) thinkingBudget else null,
        responseFormat = if (jsonMode) buildJsonObject { put("type", "json_object") } else null,
        tools = tools
    )
)

suspend fun LLMApi.chatStream(
    model: String,
    messages: List<Message>,
    tools: JsonArray? = null,
    temperature: Float = 0.9f,
    topP: Float = 0.9f,
    topK: Int = 50,
    jsonMode: Boolean = false,
    enableThinking: Boolean = true,
    thinkingBudget: Int = 4096,
    maxTokens: Int = 4096
) = postStream(
    ChatCompletionRequest(
        model = model,
        messages = messages,
        stream = true,
        temperature = temperature,
        topP = topP,
        topK = topK,
        maxTokens = maxTokens,
        enableThinking = if (enableThinking) true else null,
        thinkingBudget = if (enableThinking) thinkingBudget else null,
        responseFormat = if (jsonMode) buildJsonObject { put("type", "json_object") } else null,
        tools = tools
    )
)
