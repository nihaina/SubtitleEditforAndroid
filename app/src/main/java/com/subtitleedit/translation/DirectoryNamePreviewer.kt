package com.subtitleedit.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class DirectoryTranslationEntry(val path: String, val name: String, val isDirectory: Boolean)

internal interface PageTranslationSession {
    suspend fun missingModels(models: Collection<PageTranslationModel>): Set<PageTranslationModel>
    suspend fun identifyLanguage(text: String): PageTranslationLanguage?
    suspend fun translate(text: String, source: PageTranslationLanguage): String
}

internal data class DirectoryTranslationResult(
    val missingRequiredModels: Set<PageTranslationModel> = emptySet(),
    val missingDetectedModels: Set<PageTranslationModel> = emptySet(),
    val hasFailures: Boolean = false,
)

internal class DirectoryNamePreviewer(private val cacheSize: Int = 1024) {
    private data class CacheKey(val plan: PageTranslationPlan, val stem: String)
    private data class CachedName(val source: PageTranslationLanguage?, val translation: String? = null)

    private val cache = object : LinkedHashMap<CacheKey, CachedName>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CachedName>?): Boolean =
            size > cacheSize
    }

    suspend fun preview(
        entries: List<DirectoryTranslationEntry>,
        plan: PageTranslationPlan,
        session: PageTranslationSession,
        onTranslated: (path: String, name: String) -> Unit,
    ): DirectoryTranslationResult {
        if (plan.options.source == plan.options.target) return DirectoryTranslationResult()
        val requiredMissing = session.missingModels(plan.requiredModels)
        if (requiredMissing.isNotEmpty()) return DirectoryTranslationResult(missingRequiredModels = requiredMissing)

        val detectedMissing = linkedSetOf<PageTranslationModel>()
        val missingBySource = mutableMapOf<PageTranslationLanguage, Set<PageTranslationModel>>()
        var hasFailures = false
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            val prefix = entry.name.takeWhile { it == '.' }
            val name = entry.name.drop(prefix.length)
            val extensionStart = if (entry.isDirectory) -1 else name.lastIndexOf('.')
            val stem = if (extensionStart > 0) name.substring(0, extensionStart) else name
            if (!stem.codePoints().anyMatch(Character::isLetter)) continue

            val key = CacheKey(plan, stem)
            val translated = try {
                val cached = cache[key] ?: CachedName(plan.options.source ?: session.identifyLanguage(stem)).also {
                    cache[key] = it
                }
                val source = cached.source ?: continue
                val route = plan.route(source)
                if (route.isNullOrEmpty()) continue
                val missing = missingBySource.getOrPut(source) { session.missingModels(route) }
                if (missing.isNotEmpty()) {
                    detectedMissing += missing
                    continue
                }
                cached.translation ?: session.translate(stem, source).trim().also {
                    check(it.isNotEmpty())
                    cache[key] = cached.copy(translation = it)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                hasFailures = true
                continue
            }
            if (translated != stem) {
                currentCoroutineContext().ensureActive()
                onTranslated(entry.path, prefix + translated + name.substring(stem.length))
            }
        }
        return DirectoryTranslationResult(missingDetectedModels = detectedMissing, hasFailures = hasFailures)
    }
}
