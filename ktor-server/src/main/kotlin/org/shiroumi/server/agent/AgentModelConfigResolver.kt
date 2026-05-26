package org.shiroumi.server.agent

import model.agent.AgentModelPresetDto
import model.agent.AgentModelSelectionMode
import org.shiroumi.config.AgentConfig
import org.shiroumi.config.QuantConfig
import org.shiroumi.database.user.model.UserAgentConfigModel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EffectiveAgentModelConfig(
    val selectionMode: AgentModelSelectionMode,
    val presetKey: String?,
    val displayName: String,
    val modelId: String?,
    val baseUrl: String?,
    val apiKey: String,
    val provider: String,
)

object AgentModelConfigResolver {
    fun presets(agentConfig: AgentConfig): List<AgentModelPresetDto> {
        return agentConfig.modelPresets.map { (key, preset) ->
            AgentModelPresetDto(
                key = key,
                displayName = preset.displayName,
                modelId = preset.modelId,
                baseUrl = preset.baseUrl,
                provider = preset.provider
            )
        }
    }

    fun defaultPresetKey(agentConfig: AgentConfig): String? =
        agentConfig.defaultModelKey ?: presets(agentConfig).firstOrNull()?.key

    fun resolve(
        quantConfig: QuantConfig,
        userConfig: UserAgentConfigModel?
    ): EffectiveAgentModelConfig {
        val agentConfig = quantConfig.agent
        val presetList = presets(agentConfig)
        val presetByKey = presetList.associateBy { it.key }
        val requestedMode = runCatching {
            AgentModelSelectionMode.valueOf(userConfig?.modelSelectionMode ?: AgentModelSelectionMode.PRESET.name)
        }.getOrDefault(AgentModelSelectionMode.PRESET)

        if (requestedMode == AgentModelSelectionMode.CUSTOM && userConfig?.customModelId?.isNotBlank() == true) {
            val decryptedKey = userConfig.customApiKeyEncrypted
                ?.let { decryptApiKey(it, quantConfig) }
                .orEmpty()
            return EffectiveAgentModelConfig(
                selectionMode = AgentModelSelectionMode.CUSTOM,
                presetKey = null,
                displayName = userConfig.customModelDisplayName?.takeIf { it.isNotBlank() } ?: "自定义模型",
                modelId = userConfig.customModelId,
                baseUrl = userConfig.customBaseUrl,
                apiKey = decryptedKey,
                provider = "custom"
            )
        }

        val presetKey = userConfig?.modelPresetKey
            ?.takeIf { it in presetByKey }
            ?: defaultPresetKey(agentConfig)
        val preset = presetKey?.let { presetByKey[it] }
        val presetConfig = presetKey?.let { agentConfig.modelPresets[it] }
        return EffectiveAgentModelConfig(
            selectionMode = AgentModelSelectionMode.PRESET,
            presetKey = preset?.key,
            displayName = preset?.displayName ?: "配置文件模型",
            modelId = preset?.modelId ?: agentConfig.modelId,
            baseUrl = preset?.baseUrl ?: agentConfig.baseUrl,
            apiKey = presetConfig?.apiKey?.takeIf { it.isNotBlank() } ?: agentConfig.apiKey,
            provider = preset?.provider ?: if (agentConfig.baseUrl?.contains("openrouter.ai") == true) "openrouter" else "anthropic"
        )
    }

    fun encryptApiKey(apiKey: String, quantConfig: QuantConfig): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec(quantConfig), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decryptApiKey(encrypted: String, quantConfig: QuantConfig): String? = runCatching {
        val bytes = Base64.getDecoder().decode(encrypted)
        val iv = bytes.copyOfRange(0, 12)
        val payload = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec(quantConfig), GCMParameterSpec(128, iv))
        cipher.doFinal(payload).toString(Charsets.UTF_8)
    }.getOrNull()

    fun maskApiKey(apiKey: String?): String? {
        if (apiKey.isNullOrBlank()) return null
        return "********"
    }

    private fun keySpec(quantConfig: QuantConfig): SecretKeySpec {
        val secret = System.getenv("QUANT_AGENT_CONFIG_SECRET")
            ?: quantConfig.auth.jwt.secret
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }
}
