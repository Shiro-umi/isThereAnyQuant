package org.shiroumi.network.apis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.shiroumi.configs.BuildConfigs
import org.shiroumi.network.deepseek
import org.shiroumi.network.siliconFlow
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

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
    val index: Int,
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
}

suspend fun LLMApi.chat(
    model: String,
    messages: List<Message>,
    tools: JsonArray? = null,
    stream: Boolean = false,
    temperature: Float = 0.9f,
    topK: Int = 50
): ChatCompletion = post(
    body = pretty.encodeToString(buildJsonObject {
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
        put("top_k", topK)
//        put("enable_thinking", true)
        put("thinking_budget", 65536)
        put("max_tokens", 163840)
        tools?.let { t -> put("tools", tools) }
    }).toRequestBody(contentType = "application/json".toMediaType())
)