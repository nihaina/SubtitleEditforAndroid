package com.subtitleedit.translation

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import java.io.Closeable
import java.lang.Character.UnicodeScript
import java.util.Locale
import kotlinx.coroutines.tasks.await

internal class PageTranslationLanguageIdentifier : Closeable {
    private val client = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.8f).build(),
    )

    suspend fun identify(name: String, systemLanguage: PageTranslationLanguage?): PageTranslationLanguage? {
        val sample = filenameLanguageSample(name) ?: return null
        return languageFromIdentification(client.identifyLanguage(sample).await(), systemLanguage)
    }

    override fun close() = client.close()
}

internal fun filenameLanguageSample(name: String): String? {
    val letters = name.codePoints().filter(Character::isLetter).limit(3).toArray()
    if (letters.size < 2 || (letters.size == 2 && letters.all { UnicodeScript.of(it) == UnicodeScript.LATIN })) {
        return null
    }
    return name.replace('_', ' ')
}

internal fun languageFromIdentification(tag: String, systemLanguage: PageTranslationLanguage?): PageTranslationLanguage? {
    val locale = Locale.forLanguageTag(tag)
    if (locale.language.isEmpty() || locale.script == "Latn") return null
    val base = locale.toLanguageTag().substringBefore('-')
    return if (base == "zh") {
        when (locale.script) {
            "Hant" -> PageTranslationLanguage.TRADITIONAL_CHINESE
            "Hans" -> PageTranslationLanguage.CHINESE
            "" -> systemLanguage.takeIf { it == PageTranslationLanguage.TRADITIONAL_CHINESE }
                ?: PageTranslationLanguage.CHINESE
            else -> null
        }
    } else {
        PageTranslationLanguage.fromTag(base)
    }
}
