package com.subtitleedit.translation

import java.util.Locale

@JvmInline
internal value class PageTranslationLanguage(val tag: String) {
    fun toLocale(): Locale = Locale.forLanguageTag(tag)

    companion object {
        val CHINESE = PageTranslationLanguage("zh-Hans")
        val TRADITIONAL_CHINESE = PageTranslationLanguage("zh-Hant")
        val JAPANESE = PageTranslationLanguage("ja")
        val ENGLISH = PageTranslationLanguage("en")

        fun fromTag(tag: String?): PageTranslationLanguage? {
            val locale = tag?.let(Locale::forLanguageTag)?.takeIf { it.language.isNotEmpty() } ?: return null
            val normalized = locale.toLanguageTag()
            return when (normalized) {
                "zh" -> CHINESE
                "no" -> PageTranslationLanguage("nb")
                else -> PageTranslationLanguage(normalized)
            }
        }
    }
}

internal data class PageTranslationModel(val source: PageTranslationLanguage, val target: PageTranslationLanguage) {
    val id: String get() = "${source.tag}-${target.tag}"
}

internal data class PageTranslationOptions(
    val source: PageTranslationLanguage? = null,
    val target: PageTranslationLanguage = PageTranslationLanguage.CHINESE,
)

internal class PageTranslationCatalog(val models: Set<PageTranslationModel>) {
    private val modelsById = models.associateBy(PageTranslationModel::id)
    val sources = models.mapTo(linkedSetOf(), PageTranslationModel::source)
    val targets = models.mapTo(linkedSetOf(), PageTranslationModel::target)

    fun model(id: String?): PageTranslationModel? = modelsById[id]

    fun route(source: PageTranslationLanguage, target: PageTranslationLanguage): List<PageTranslationModel>? {
        if (source == target) return emptyList()
        val direct = PageTranslationModel(source, target)
        if (direct in models) return listOf(direct)
        val first = PageTranslationModel(source, PageTranslationLanguage.ENGLISH)
        val second = PageTranslationModel(PageTranslationLanguage.ENGLISH, target)
        return if (first in models && second in models) listOf(first, second) else null
    }

    fun normalize(options: PageTranslationOptions): PageTranslationOptions {
        val source = options.source?.takeIf { it in sources }
        val availableTargets = targetsFor(source)
        return PageTranslationOptions(
            source = source,
            target = options.target.takeIf { it in availableTargets }
                ?: PageTranslationLanguage.CHINESE.takeIf { it in availableTargets }
                ?: availableTargets.first(),
        )
    }

    fun targetsFor(source: PageTranslationLanguage?): Set<PageTranslationLanguage> = if (source == null) targets else
        targets.filterTo(linkedSetOf()) { route(source, it) != null }

    fun plan(options: PageTranslationOptions, systemLocale: Locale): PageTranslationPlan {
        val selection = normalize(options)
        val systemLanguage = if (selection.source == null) systemLanguage(systemLocale)?.takeIf {
            route(it, selection.target) != null
        } else null
        return PageTranslationPlan(selection, systemLanguage, this)
    }

    private fun systemLanguage(locale: Locale): PageTranslationLanguage? {
        val tag = locale.toLanguageTag()
        val base = tag.substringBefore('-')
        val scriptTag = locale.script.takeIf(String::isNotEmpty)?.let { "$base-$it" }
        return sequenceOf(tag, scriptTag, base)
            .mapNotNull(PageTranslationLanguage::fromTag)
            .firstOrNull { it in sources }
    }
}

internal data class PageTranslationPlan(
    val options: PageTranslationOptions,
    val systemLanguage: PageTranslationLanguage?,
    private val catalog: PageTranslationCatalog,
) {
    private val requiredSources = options.source?.let(::setOf) ?: setOfNotNull(
        PageTranslationLanguage.CHINESE, PageTranslationLanguage.JAPANESE, PageTranslationLanguage.ENGLISH,
        systemLanguage,
    )
    val requiredModels: Set<PageTranslationModel> = requiredSources.flatMap { route(it).orEmpty() }.toSet()

    fun route(source: PageTranslationLanguage): List<PageTranslationModel>? {
        if (options.source != null && source != options.source) return null
        return catalog.route(source, options.target)
    }
}
