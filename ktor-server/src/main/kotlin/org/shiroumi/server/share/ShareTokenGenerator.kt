package org.shiroumi.server.share

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 分享 token / blockKey / IP 哈希工具。
 *
 * - shareToken：22 字符 base62（≈ 130 bit 熵），URL 安全，不可枚举
 * - blockKey：对 K 线参数规范化后取 SHA-256 前 16 字符 hex，URL 安全
 * - ipHash：SHA-256(salt + ip) hex，避免裸 IP 入库
 */
object ShareTokenGenerator {

    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val random = SecureRandom()

    private val ipSalt: String = System.getProperty("quant.share.ipSalt")
        ?: System.getenv("QUANT_SHARE_IP_SALT")
        ?: "quant-share-default-salt"

    fun newShareToken(length: Int = 22): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(BASE62[random.nextInt(BASE62.length)])
        }
        return sb.toString()
    }

    /**
     * 把 K 线参数序列化成一段 canonical 字符串后取 SHA-256 前 16 字符。
     * 任何字段差异都会改变 blockKey，确保匿名接口请求的参数必须与分享时刻完全一致。
     */
    fun blockKey(
        tsCode: String,
        period: String,
        startDate: String?,
        endDate: String?,
        limitCount: Int?,
        indicators: String?,
        useAdjusted: Boolean,
    ): String {
        val canonical = listOf(
            tsCode,
            period,
            startDate.orEmpty(),
            endDate.orEmpty(),
            limitCount?.toString().orEmpty(),
            indicators.orEmpty(),
            useAdjusted.toString(),
        ).joinToString("|")
        return sha256Hex(canonical).substring(0, 16)
    }

    fun ipHash(ip: String?): String? {
        if (ip.isNullOrBlank()) return null
        return sha256Hex(ipSalt + "|" + ip).substring(0, 32)
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(((b.toInt() ushr 4) and 0xF).toString(16))
            sb.append((b.toInt() and 0xF).toString(16))
        }
        return sb.toString()
    }
}
