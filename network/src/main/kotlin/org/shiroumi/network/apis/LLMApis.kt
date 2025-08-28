package org.shiroumi.network.apis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.shiroumi.configs.BuildConfigs
import org.shiroumi.network.deepseek
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

@Serializable
data class ChatCompletionUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
    @SerialName("prompt_tokens_details") val promptTokensDetails: Map<String, Int>,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int
)

@Serializable
data class Message(
    val role: String,
    val content: String
)

@Serializable
data class Choice(
    val index: Int,
    val message: Message,
    val logprobs: String?,
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

val llmApi: LLMApi by deepseek()

private val pretty = Json { prettyPrint = true }

interface LLMApi {
    @POST("chat/completions")
    @Headers(
        "Content-Type: application/json",
        "Accept: application/json",
        "Authorization: Bearer ${BuildConfigs.LLM_SECRET}"
    )
    suspend fun post(@Body body: RequestBody?): ChatCompletion
}

suspend fun LLMApi.chat(
    model: String,
    messages: List<Message>,
    stream: Boolean = false
): ChatCompletion = post(
    body = pretty.encodeToString(buildJsonObject {
        putJsonArray("messages") {
            messages.forEach { msg ->
                add(buildJsonObject {
                    put("content", msg.content)
                    put("role", msg.role)
                })
            }
        }
        put("model", model)
        put("stream", stream)
    }).toRequestBody(contentType = "application/json".toMediaType())
)



