package org.shiroumi.config

/**
 * 纯 config.yaml 口径的 agent 模型解析。
 *
 * 业务定位：生产链路的 [org.shiroumi.server.agent.AgentModelConfigResolver]（ktor-server）会叠加数据库里
 * 用户级模型覆盖（CUSTOM/PRESET、加密 apiKey），依赖 user 表，只能在 server 进程内用。
 * 离线批处理（cli batch-agent-driver）没有用户上下文、也不应触碰 user 表，只需要从 config.yaml 的 agent 段
 * 取「启动 Claude Code 进程」所需的 modelId / baseUrl / apiKey / provider。
 *
 * 本对象只覆盖 config 维度的解析（preset by key -> 默认 preset -> 顶层 modelId/baseUrl/apiKey），
 * 与生产 resolver 的 PRESET 分支同口径，不引入用户级覆盖，保持模块边界干净。
 */
object AgentModelResolution {

    /** 解析后的可直接喂给 AgentBridge.Config 的模型四元组。 */
    data class Resolved(
        val modelId: String?,
        val baseUrl: String?,
        val apiKey: String,
        val provider: String,
    )

    /**
     * @param agentConfig config.yaml 的 agent 段
     * @param presetKey 显式指定的预设 key；为空时回退 agent.defaultModelKey，再回退首个预设
     */
    fun resolve(agentConfig: AgentConfig, presetKey: String? = null): Resolved {
        val effectiveKey = presetKey
            ?.takeIf { it in agentConfig.modelPresets }
            ?: agentConfig.defaultModelKey?.takeIf { it in agentConfig.modelPresets }
            ?: agentConfig.modelPresets.keys.firstOrNull()
        val preset = effectiveKey?.let { agentConfig.modelPresets[it] }

        return Resolved(
            modelId = preset?.modelId ?: agentConfig.modelId,
            baseUrl = preset?.baseUrl ?: agentConfig.baseUrl,
            apiKey = preset?.apiKey?.takeIf { it.isNotBlank() } ?: agentConfig.apiKey,
            provider = preset?.provider
                ?: if (agentConfig.baseUrl?.contains("openrouter.ai") == true) "openrouter" else "anthropic",
        )
    }
}
