package org.shiroumi.quant_kmp.ui.agent.sidebar

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Skill预设数据类
 *
 * @param skillId Skill唯一标识
 * @param label 中文显示名称
 * @param icon Material3图标
 * @param description 简短描述
 * @param promptTemplate Prompt模板，包含{name}和{code}占位符
 */
data class SkillPreset(
    val skillId: String,
    val analysisType: String = "general",
    val label: String,
    val icon: ImageVector,
    val description: String,
    val promptTemplate: String
)

data class AgentPresetPrompt(
    val prompt: String,
    val analysisType: String
)
