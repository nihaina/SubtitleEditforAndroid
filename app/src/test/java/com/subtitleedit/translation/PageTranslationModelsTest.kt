package com.subtitleedit.translation

import com.subtitleedit.translation.PageTranslationLanguage.Companion.CHINESE
import com.subtitleedit.translation.PageTranslationLanguage.Companion.ENGLISH
import com.subtitleedit.translation.PageTranslationLanguage.Companion.JAPANESE
import com.subtitleedit.translation.PageTranslationLanguage.Companion.TRADITIONAL_CHINESE
import io.github.yinvoker.foxlet.LanguagePair
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal val JA_EN = PageTranslationModel(JAPANESE, ENGLISH)
internal val EN_JA = PageTranslationModel(ENGLISH, JAPANESE)
internal val ZH_EN = PageTranslationModel(CHINESE, ENGLISH)
internal val EN_ZH = PageTranslationModel(ENGLISH, CHINESE)
internal val FRENCH = PageTranslationLanguage("fr")
internal val FR_EN = PageTranslationModel(FRENCH, ENGLISH)
internal val TEST_TRANSLATION_CATALOG = PageTranslationCatalog(setOf(
    JA_EN, EN_JA, ZH_EN, EN_ZH, FR_EN, PageTranslationModel(ENGLISH, FRENCH),
    PageTranslationModel(TRADITIONAL_CHINESE, ENGLISH), PageTranslationModel(ENGLISH, TRADITIONAL_CHINESE),
))

class PageTranslationModelsTest {
    @Test fun chineseAndJapanesePivotThroughEnglishInOrder() {
        assertEquals(listOf(JA_EN, EN_ZH), TEST_TRANSLATION_CATALOG.route(JAPANESE, CHINESE))
        assertEquals(listOf(ZH_EN, EN_JA), TEST_TRANSLATION_CATALOG.route(CHINESE, JAPANESE))
    }

    @Test fun directModelWinsOverPivotAndEqualLanguagesUseNoModel() {
        val direct = PageTranslationModel(JAPANESE, CHINESE)
        val catalog = PageTranslationCatalog(TEST_TRANSLATION_CATALOG.models + direct)

        assertEquals(listOf(direct), catalog.route(JAPANESE, CHINESE))
        assertEquals(listOf(EN_ZH), catalog.route(ENGLISH, CHINESE))
        assertEquals(listOf(ZH_EN), catalog.route(CHINESE, ENGLISH))
        assertEquals(emptyList<PageTranslationModel>(), catalog.route(CHINESE, CHINESE))
    }

    @Test fun autoIncludesSupportedSystemLanguageAndDeduplicatesSharedPacks() {
        val plan = TEST_TRANSLATION_CATALOG.plan(PageTranslationOptions(target = JAPANESE), Locale.CANADA_FRENCH)

        assertEquals(FRENCH, plan.systemLanguage)
        assertEquals(setOf(ZH_EN, FR_EN, EN_JA), plan.requiredModels)
    }

    @Test fun autoIgnoresUnsupportedSystemLanguageAndDoesNotCountDefaultLanguagesTwice() {
        val options = PageTranslationOptions()
        val unsupported = TEST_TRANSLATION_CATALOG.plan(options, Locale.forLanguageTag("eo"))
        val japanese = TEST_TRANSLATION_CATALOG.plan(options, Locale.JAPAN)

        assertNull(unsupported.systemLanguage)
        assertEquals(JAPANESE, japanese.systemLanguage)
        assertEquals(setOf(JA_EN, EN_ZH), japanese.requiredModels)
        assertEquals(japanese.requiredModels, unsupported.requiredModels)
        assertEquals(listOf(FR_EN, EN_ZH), japanese.route(FRENCH))
    }

    @Test fun explicitSourceDoesNotRequireDefaultOrSystemLanguagePacks() {
        val plan = TEST_TRANSLATION_CATALOG.plan(PageTranslationOptions(ENGLISH, JAPANESE), Locale.FRANCE)

        assertEquals(listOf(EN_JA), plan.route(ENGLISH))
        assertNull(plan.route(FRENCH))
        assertEquals(setOf(EN_JA), plan.requiredModels)
    }

    @Test fun preservesScriptedChineseAndAcceptsLegacySimplifiedChineseTag() {
        assertEquals(CHINESE, PageTranslationLanguage.fromTag("zh"))
        assertEquals(TRADITIONAL_CHINESE, PageTranslationLanguage.fromTag("zh-hant"))
        assertEquals(EN_ZH, TEST_TRANSLATION_CATALOG.model("en-zh-Hans"))
        assertNull(TEST_TRANSLATION_CATALOG.model("en-zh"))

        val plan = TEST_TRANSLATION_CATALOG.plan(PageTranslationOptions(), Locale.forLanguageTag("zh-Hant-TW"))
        assertEquals(TRADITIONAL_CHINESE, plan.systemLanguage)
        assertEquals(setOf(JA_EN, EN_ZH, PageTranslationModel(TRADITIONAL_CHINESE, ENGLISH)), plan.requiredModels)
    }

    @Test fun bundledFoxletCatalogIncludesAllPinnedDirectionsAndOneWayLanguages() {
        val catalog = requireNotNull(LanguagePair::class.java.getResourceAsStream("models.tsv"))
            .bufferedReader().useLines { lines ->
                val directions = lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                    val (source, target) = line.split('\t', limit = 3)
                    PageTranslationModel(PageTranslationLanguage(source), PageTranslationLanguage(target))
                }.toSet()
                PageTranslationCatalog(directions)
            }
        val albanian = PageTranslationLanguage("sq")
        val azerbaijani = PageTranslationLanguage("az")

        assertEquals(106, catalog.models.size)
        assertEquals(54, catalog.sources.size)
        assertEquals(54, catalog.targets.size)
        assertTrue(albanian in catalog.sources)
        assertFalse(albanian in catalog.targets)
        assertFalse(azerbaijani in catalog.sources)
        assertTrue(azerbaijani in catalog.targetsFor(albanian))
        assertNull(catalog.route(azerbaijani, albanian))
        assertTrue(catalog.sources.all { catalog.targetsFor(it) == catalog.targets })
    }

    @Test fun mapsIdentifiedTagsToFoxletLanguages() {
        assertEquals(FRENCH, languageFromIdentification("fr-CA", ENGLISH))
        assertEquals(PageTranslationLanguage("ko"), languageFromIdentification("ko", ENGLISH))
        assertEquals(PageTranslationLanguage("nb"), languageFromIdentification("no", null))
        assertEquals(PageTranslationLanguage("he"), languageFromIdentification("iw", null))
        assertEquals(TRADITIONAL_CHINESE, languageFromIdentification("zh-Hant", CHINESE))
        assertEquals(CHINESE, languageFromIdentification("zh-Hans", TRADITIONAL_CHINESE))
        assertEquals(TRADITIONAL_CHINESE, languageFromIdentification("zh", TRADITIONAL_CHINESE))
        assertEquals(CHINESE, languageFromIdentification("zh", FRENCH))
    }

    @Test fun unknownAndRomanizedTagsAreNotSilentlyTreatedAsEnglish() {
        assertNull(languageFromIdentification("und", ENGLISH))
        assertNull(languageFromIdentification("ja-Latn", JAPANESE))
        assertNull(languageFromIdentification("zh-Latn", CHINESE))
    }

    @Test fun skipsAmbiguousShortNamesButKeepsShortNonLatinTitles() {
        assertNull(filenameLanguageSample("02_🎶"))
        assertNull(filenameLanguageSample("AB 04"))
        assertNull(filenameLanguageSample("雨"))
        assertEquals("夜雨", filenameLanguageSample("夜雨"))
        assertEquals("La pluie du soir", filenameLanguageSample("La_pluie_du_soir"))
    }
}
