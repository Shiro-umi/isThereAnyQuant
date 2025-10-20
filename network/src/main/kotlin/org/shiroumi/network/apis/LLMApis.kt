package org.shiroumi.network.apis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.shiroumi.configs.BuildConfigs
import org.shiroumi.network.deepseek
import org.shiroumi.network.siliconFlow
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Streaming

@Serializable
data class ChatCompletionUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
//    @SerialName("prompt_tokens_details") val promptTokensDetails: Map<String, Int>? = null,
//    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
//    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int? = null
)

@Serializable
data class ToolCalls(
//    val index: Int,
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
    @SerialName("reasoning_content") val reasoningContent: String? = null
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

val deepseek: LLMApi by deepseek()

val siliconFlow: LLMApi by siliconFlow()

private val pretty = Json { prettyPrint = true }

interface LLMApi {
    @POST("v1/chat/completions")
    @Headers(
        "Content-Type: application/json",
        "Accept: application/json",
        "Authorization: Bearer ${BuildConfigs.LLM_SECRET_SILICONFLOW}"
    )
    suspend fun post(@Body body: RequestBody?): ChatCompletion

    @POST("v1/chat/completions")
    @Streaming
    @Headers(
        "Content-Type: application/json",
        "Accept: application/json",
        "Authorization: Bearer ${BuildConfigs.LLM_SECRET_SILICONFLOW}"
    )
    suspend fun postStream(@Body body: RequestBody?): ResponseBody
}

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
    getRequestBody(
        model = model,
        messages = messages,
        tools = tools,
        temperature = temperature,
        topP = topP,
        topK = topK,
        jsonMode = jsonMode,
        enableThinking = enableThinking,
        thinkingBudget = thinkingBudget,
        maxTokens = maxTokens,
        stream = false
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
    getRequestBody(
        model = model,
        messages = messages,
        tools = tools,
        temperature = temperature,
        topP = topP,
        topK = topK,
        jsonMode = jsonMode,
        enableThinking = enableThinking,
        thinkingBudget = thinkingBudget,
        maxTokens = maxTokens,
        stream = true
    )
)

private fun getRequestBody(
    model: String,
    messages: List<Message>,
    tools: JsonArray? = null,
    temperature: Float = 0.9f,
    topP: Float = 0.9f,
    topK: Int = 50,
    jsonMode: Boolean = false,
    enableThinking: Boolean = true,
    thinkingBudget: Int = 4096,
    maxTokens: Int = 4096,
    stream: Boolean = false
) = pretty.encodeToString(buildJsonObject {
    putJsonArray("messages") {
        messages.forEach { msg ->
            add(
                pretty.encodeToJsonElement(Message.serializer(), msg)
            )
        }
    }
    put("model", model)
    put("stream", stream)
    put("temperature", temperature)
    put("top_p", topP)
    put("top_k", topK)
    if (enableThinking) {
        put("enable_thinking", true)
        put("thinking_budget", thinkingBudget)
    }
    put("max_tokens", maxTokens)
    if (jsonMode) put("response_format", buildJsonObject {
        put("type", "json_object")
    })
    tools?.let { t -> put("tools", t) }
}).toRequestBody(contentType = "application/json".toMediaType())