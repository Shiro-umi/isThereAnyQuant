package org.shiroumi.agent.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * 技能元数据
 */
data class Skill(
    val name: String,
    val description: String,
    val triggers: List<String>,
    val path: String,
    val allowedTools: List<String> = emptyList()
)

data class SkillDiagnostic(
    val name: String,
    val path: String,
    val allowedTools: List<String>,
    val builtin: Boolean,
    val namingValid: Boolean,
    val issues: List<String>
)

/**
 * 技能管理器
 * 负责解析 .claude/skills 目录下的技能定义
 */
class SkillManager(private val workDir: String) {

    private val skillsDir = File(workDir, ".claude/skills")
    private val kebabCaseRegex = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val camelCaseRegex = Regex("^[a-z][A-Za-z0-9]*$")

    /**
     * 扫描并加载所有技能
     */
    fun discoverSkills(): List<Skill> {
        return discoverDiagnostics().mapNotNull { diagnostic ->
            val skillFile = File(workDir, diagnostic.path)
            parseSkill(skillFile)
        }
    }

    /**
     * 扫描并返回技能诊断信息
     */
    fun discoverDiagnostics(): List<SkillDiagnostic> {
        if (!skillsDir.exists() || !skillsDir.isDirectory) {
            logger.warn { "[SkillManager] ⚠️ Skills directory not found: ${skillsDir.absolutePath}" }
            return emptyList()
        }

        val diagnostics = mutableListOf<SkillDiagnostic>()

        skillsDir
            .walkTopDown()
            .filter { it.isFile && it.name == "SKILL.md" }
            .forEach { skillFile ->
                try {
                    parseSkill(skillFile)?.let { skill ->
                        diagnostics.add(buildDiagnostic(skillFile, skill))
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[SkillManager] ✘ Failed to parse skill in ${skillFile.parentFile.name}" }
                }
            }

        logger.info { "[SkillManager] ✔ Discovered ${diagnostics.size} skills in ${skillsDir.name}" }
        return diagnostics
    }

    /**
     * 解析 SKILL.md 中的 YAML Frontmatter
     */
    private fun parseSkill(file: File): Skill? {
        val content = file.readText()
        val lines = content.lines()
        
        if (lines.isEmpty() || lines[0] != "---") return null
        
        val frontmatter = mutableListOf<String>()
        var i = 1
        while (i < lines.size && lines[i] != "---") {
            frontmatter.add(lines[i])
            i++
        }
        
        val yaml = frontmatter.joinToString("\n")
        val name = Regex("name:\\s*(.+)").find(yaml)?.groupValues?.get(1)?.trim() ?: file.parentFile.name
        
        // 提取 description (支持多行)
        val description = extractDescription(yaml)
        
        // 提取触发时机 (从正文提取更准确)
        val triggers = extractTriggersFromContent(content)

        return Skill(
            name = name,
            description = description,
            triggers = triggers,
            path = file.toRelativeString(File(workDir)),
            allowedTools = extractAllowedTools(yaml)
        )
    }

    private fun buildDiagnostic(file: File, skill: Skill): SkillDiagnostic {
        val issues = mutableListOf<String>()
        val relativePath = skill.path
        val directoryName = file.parentFile.name
        val builtin = relativePath.contains("/builtin/")
        val namingValid = kebabCaseRegex.matches(skill.name) && skill.name == directoryName

        if (!kebabCaseRegex.matches(skill.name)) {
            issues.add("Skill name must use kebab-case: ${skill.name}")
        }
        if (skill.name != directoryName) {
            issues.add("Skill name does not match directory name: $directoryName")
        }
        skill.allowedTools
            .filterNot { camelCaseRegex.matches(it) }
            .forEach { issues.add("Tool name must use camelCase: $it") }
        if (skill.allowedTools.contains("evaluateMathExpressions")) {
            issues.add("Removed math tool referenced: evaluateMathExpressions")
        }

        val metadataFile = File(file.parentFile, "metadata.yaml")
        if (metadataFile.exists()) {
            val metadataName = extractSimpleYamlValue(metadataFile.readText(), "name")
            if (metadataName != null && metadataName != skill.name) {
                issues.add("metadata.yaml name mismatch: $metadataName")
            }
        }

        val content = file.readText()
        val obviousToolMisusePatterns = listOf(
            "通过 ${skill.name} 工具",
            "调用 ${skill.name} 工具",
            "运行 ${skill.name}",
            "`${
                skill.name
            }` 工具"
        )
        if (obviousToolMisusePatterns.any { content.contains(it) }) {
            issues.add("Skill body appears to use the skill name as a tool or command")
        }
        if (content.contains("evaluate-math-expressions") || content.contains("evaluateMathExpressions")) {
            issues.add("Removed math skill/tool referenced; use shell bc instead")
        }

        return SkillDiagnostic(
            name = skill.name,
            path = relativePath,
            allowedTools = skill.allowedTools,
            builtin = builtin,
            namingValid = namingValid,
            issues = issues
        )
    }

    private fun extractDescription(yaml: String): String {
        val match = Regex("description:\\s*\\|?\\s*([\\s\\S]+?)(?=\\n\\w+:|$)").find(yaml)
        return match?.groupValues?.get(1)?.trim()?.replace(Regex("\\n\\s+"), " ") ?: ""
    }

    private fun extractAllowedTools(yaml: String): List<String> {
        val lines = yaml.lines()
        val tools = mutableListOf<String>()
        var inAllowedTools = false

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed == "allowedTools:" -> inAllowedTools = true
                inAllowedTools && line.startsWith("  - ") -> tools.add(trimmed.removePrefix("- ").trim())
                inAllowedTools && trimmed.isBlank() -> continue
                inAllowedTools -> break
            }
        }

        return tools
    }

    private fun extractSimpleYamlValue(yaml: String, key: String): String? {
        return Regex("^$key:\\s*(.+)$", RegexOption.MULTILINE)
            .find(yaml)
            ?.groupValues
            ?.get(1)
            ?.trim()
    }

    private fun extractTriggersFromContent(content: String): List<String> {
        val triggers = mutableListOf<String>()
        val triggerSectionRegex = Regex("触发时机[：:]([\\s\\S]+?)(?=\\n##|\\n---|\$)", RegexOption.IGNORE_CASE)
        val match = triggerSectionRegex.find(content)
        
        if (match != null) {
            val section = match.groupValues[1]
            Regex("-\\s*(.+)").findAll(section).forEach {
                triggers.add(it.groupValues[1].trim())
            }
        }
        return triggers
    }

}
