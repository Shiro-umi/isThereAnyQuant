package org.shiroumi.agent.security

class PathSanitizer(workDir: String) {

    private val replacements: List<Pair<Regex, String>>

    init {
        val normalized = workDir.trimEnd('/')
        val parts = normalized.split("/").filter { it.isNotEmpty() }
        val candidates = mutableListOf<Pair<String, String>>()
        candidates.add(normalized to ".")
        if (parts.size >= 2) {
            val parent = "/" + parts.dropLast(1).joinToString("/")
            candidates.add(parent to "[FILTERED]")
        }
        replacements = candidates.map { (prefix, replacement) ->
            Regex(Regex.escape(prefix)) to replacement
        }
    }

    fun sanitize(text: String): String {
        var result = text
        for ((pattern, replacement) in replacements) {
            result = pattern.replace(result, replacement)
        }
        return result
    }
}
