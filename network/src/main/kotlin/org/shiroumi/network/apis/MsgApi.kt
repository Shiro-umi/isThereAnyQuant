package org.shiroumi.network.apis

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import io.ktor.client.call.*
import io.ktor.client.request.*
import org.shiroumi.network.ApiClient

/**
 * 自定义序列化器，处理createtime字段的两种格式：
 * 1. 数字时间戳：1769990970054
 * 2. 字符串格式："2026-01-28 01:35:45"
 */
object FlexibleTimestampSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleTimestamp", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder
        return if (jsonDecoder != null) {
            val element = jsonDecoder.decodeJsonElement()
            when (element) {
                is JsonPrimitive -> {
                    element.longOrNull?.toString() ?: element.content
                }
                else -> element.toString()
            }
        } else {
            decoder.decodeString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

/**
 * 自定义序列化器，将msg字段从JSON字符串解析为List<MsgContent>
 * 例如："[\{"type":"text","msg":"今天 无\"}]" -> List<MsgContent>
 */
object MsgContentListSerializer : KSerializer<List<MsgContent>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MsgContentList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<MsgContent> {
        val jsonString = decoder.decodeString()
        return try {
            kotlinx.serialization.json.Json.decodeFromString(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<MsgContent>) {
        val jsonString = kotlinx.serialization.json.Json.encodeToString(value)
        encoder.encodeString(jsonString)
    }
}

/**
 * 消息内容项
 * msg字段是一个JSON字符串数组，包含不同类型的消息内容
 * 例如：[{"type":"text","msg":"今天 无"}]
 * 或：[{"type":"file","url":"https://...","name":"文件名.docx"}]
 * 或：[{"type":"pic","url":"https://..."}]
 */
@Serializable
data class MsgContent(
    val type: String,
    val msg: String? = null,      // text类型使用
    val url: String? = null,      // file和pic类型使用
    val name: String? = null      // file类型使用
)

/**
 * 消息列表请求体
 * 根据实际需求修改字段
 */
@Serializable
data class MsgListRequest(
    val rid: Int,
    val msgid: Int,
    val tt: Long
)

/**
 * 消息列表响应体
 */
@Serializable
data class MsgListResponse(
    val code: Int,
    val msg: String,
    val list: List<MsgItem>
)

/**
 * 消息项
 * 注意：createtime字段可能是Long(时间戳)或String(格式化时间)
 * msg字段是JSON字符串数组，会自动解析为List<MsgContent>
 */
@Serializable
data class MsgItem(
    val id: Long,
    val uid: Int,
    val rid: Int,
    @Serializable(with = MsgContentListSerializer::class)
    val msg: List<MsgContent>,
    val oid: Long,
    val type: Int? = null,
    @Serializable(with = FlexibleTimestampSerializer::class)
    val createtime: String? = null
)

/**
 * 消息API接口
 */
interface MsgApi {
    suspend fun getMsgList(
        ad: String,
        i: String,
        token: String,
        version: String,
        body: MsgListRequest
    ): MsgListResponse
}

/**
 * 消息API实现
 */
class MsgApiImpl(private val client: ApiClient) : MsgApi {
    override suspend fun getMsgList(
        ad: String,
        i: String,
        token: String,
        version: String,
        body: MsgListRequest
    ): MsgListResponse {
        return client.http.post {
            url("${client.baseUrl}/4/api/msg/list")
            header("AD", ad)
            header("i", i)
            header("token", token)
            header("version", version)
            header("Accept", "*/*")
            header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            header("Origin", "http://43.142.47.227:10002")
            header("Referer", "http://43.142.47.227:10002/")
            header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36 Edg/144.0.0.0")
            setBody(body)
        }.body()
    }
}

/**
 * 创建MsgApi实例
 */
val msgApi: MsgApi by org.shiroumi.network.msgApi()
