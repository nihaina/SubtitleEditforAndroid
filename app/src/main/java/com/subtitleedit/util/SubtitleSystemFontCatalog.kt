package com.subtitleedit.util

/** Parses Android font configuration into installed family definitions and aliases. */
internal object SubtitleSystemFontCatalog {
    data class FontReference(
        val fileName: String,
        val collectionIndex: Int = 0,
        val weight: Int = 400,
        val italic: Boolean = false
    )
    data class FamilyDefinition(val name: String, val fonts: List<FontReference>)
    data class InstalledFace(
        val name: String,
        val path: String,
        val collectionIndex: Int = 0,
        val weight: Int = 400,
        val italic: Boolean = false
    )
    data class ResolvedCatalog(
        val choices: List<InstalledFace>,
        val names: Map<String, InstalledFace>
    )

    private val familyRegex = Regex(
        "<family\\b([^>]*)>(.*?)</family\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val aliasRegex = Regex("<alias\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
    private val fontRegex = Regex(
        "<font\\b([^>]*?)(?:>(.*?)</font\\s*>|/>)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val attributeRegex = Regex("([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]")
    private val genericNames = setOf(
        "sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui",
        "sans-serif-light", "sans-serif-thin", "sans-serif-medium", "sans-serif-black",
        "sans-serif-condensed", "sans-serif-condensed-light", "sans-serif-condensed-medium",
        "sans-serif-condensed-bold", "sans-serif-smallcaps", "sans-serif-rounded",
        "serif-monospace"
    )

    /**
     * Keeps real OpenType names for both Android preview and libass. Android aliases
     * remain usable when reading existing subtitles, but are not separate font choices.
     */
    fun resolve(configs: List<String>, installedFaces: List<InstalledFace>): ResolvedCatalog {
        val faces = installedFaces.sortedWith(
            compareBy<InstalledFace> { it.italic }.thenBy { kotlin.math.abs(it.weight - 400) }
        )
        val actualFamilies = faces.distinctBy { it.name.lowercase() }
        val lookup = actualFamilies.associateByTo(linkedMapOf()) { it.name.lowercase() }
        families(configs, faces.mapTo(hashSetOf()) { it.path }).forEach { family ->
            val configured = family.fonts.firstNotNullOfOrNull { font ->
                faces.firstOrNull {
                    normalizeFile(it.path) == font.fileName && it.collectionIndex == font.collectionIndex
                }
            }
            if (configured != null) {
                // The regular face of this actual family matches a normal subtitle style.
                lookup.putIfAbsent(family.name.lowercase(), lookup[configured.name.lowercase()] ?: configured)
            }
        }
        val aliases = aliases(configs)
        aliases.forEach { (alias, initialTarget) ->
            var target = initialTarget.lowercase()
            val visited = mutableSetOf(alias)
            while (visited.add(target)) {
                val resolved = lookup[target]
                if (resolved != null) {
                    lookup.putIfAbsent(alias, resolved)
                    break
                }
                target = aliases[target]?.lowercase() ?: break
            }
        }
        return ResolvedCatalog(
            actualFamilies.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            lookup
        )
    }

    /** Returns configured families whose referenced font files are present. */
    fun families(configs: List<String>, availableFiles: Set<String>): List<FamilyDefinition> {
        val files = availableFiles.mapTo(hashSetOf()) { normalizeFile(it) }
        val result = linkedMapOf<String, FamilyDefinition>()
        configs.forEach { config ->
            familyRegex.findAll(config).forEach { match ->
                val attrs = attributes(match.groupValues[1])
                val name = attrs["name"]?.trim().orEmpty()
                if (name.isBlank()) return@forEach
                val configuredFiles = fontRegex.findAll(match.groupValues[2])
                    .mapNotNull { font ->
                        val fontAttrs = attributes(font.groupValues[1])
                        val configuredFile = (sequenceOf(font.groupValues[2].trim()) +
                            sequenceOf("file", "filename", "path").mapNotNull { fontAttrs[it]?.trim() })
                            .map(::normalizeFile).firstOrNull { it.isNotBlank() && it in files }
                            ?: return@mapNotNull null
                        FontReference(
                            configuredFile,
                            fontAttrs["index"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            fontAttrs["weight"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 400,
                            fontAttrs["style"].equals("italic", true)
                        )
                    }
                    .distinct()
                    .sortedWith(compareBy<FontReference> { it.italic }.thenBy { kotlin.math.abs(it.weight - 400) })
                    .toList()
                if (configuredFiles.isNotEmpty()) {
                    val key = name.lowercase()
                    val old = result[key]
                    result[key] = FamilyDefinition(
                        old?.name ?: name,
                        (old?.fonts.orEmpty() + configuredFiles).distinct()
                    )
                }
            }
        }
        return result.values.toList()
    }

    /** Compatibility API: configured real families plus aliases that resolve to them. */
    fun parse(configs: List<String>, availableFiles: Set<String>): List<String> {
        val families = families(configs, availableFiles)
        val familyNames = families.map { it.name }.toMutableList()
        val familyKeys = families.mapTo(hashSetOf()) { it.name.lowercase() }
        val aliases = aliases(configs)
        aliases.forEach { (alias, target) ->
            if (resolvesToFamily(target, familyKeys, aliases)) familyNames += alias
        }
        return familyNames.distinctBy { it.lowercase() }
    }

    /** Alias map is retained for resolving generic Android aliases to concrete families. */
    fun aliases(configs: List<String>): Map<String, String> = buildMap {
        configs.forEach { config ->
            aliasRegex.findAll(config).forEach { match ->
                val attrs = attributes(match.groupValues[1])
                val alias = attrs["name"]?.trim().orEmpty()
                val target = attrs["to"]?.trim().orEmpty()
                if (alias.isNotBlank() && target.isNotBlank()) put(alias.lowercase(), target)
            }
        }
    }

    fun isGeneric(name: String): Boolean = name.trim().lowercase() in genericNames

    private fun resolvesToFamily(
        initial: String,
        families: Set<String>,
        aliases: Map<String, String>
    ): Boolean {
        var current = initial.trim()
        val visited = mutableSetOf<String>()
        while (visited.add(current.lowercase())) {
            if (current.lowercase() in families) return true
            current = aliases[current.lowercase()] ?: return false
        }
        return false
    }

    private fun normalizeFile(value: String): String =
        value.trim().substringAfterLast('/').substringAfterLast('\\').lowercase()

    private fun attributes(value: String): Map<String, String> =
        attributeRegex.findAll(value).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
}
