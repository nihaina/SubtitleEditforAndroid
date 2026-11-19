package com.subtitleedit.translation

import com.subtitleedit.translation.PageTranslationLanguage.Companion.CHINESE
import com.subtitleedit.translation.PageTranslationLanguage.Companion.ENGLISH
import com.subtitleedit.translation.PageTranslationLanguage.Companion.JAPANESE
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DirectoryNamePreviewerTest {
    private val previewer = DirectoryNamePreviewer()
    private val translatedNames = mutableMapOf<String, String>()

    @Test fun translatesOnlyDisplayNamesAndKeepsFileExtensionsAndHiddenPrefix() = runBlocking {
        val files = listOf(
            DirectoryTranslationEntry("/root/.Rain.WAV", ".Rain.WAV", false),
            DirectoryTranslationEntry("/root/Rain.srt", "Rain.srt", false),
            DirectoryTranslationEntry("/root/Rain.v2", "Rain.v2", true),
            DirectoryTranslationEntry("/root/..", "..", true),
            DirectoryTranslationEntry("/root/123.png", "123.png", false),
        )
        val session = FakeSession(translate = { text, _ -> if (text == "Rain.v2") "雨.v2" else "雨" })
        val result = preview(files, PageTranslationOptions(ENGLISH, CHINESE), setOf(EN_ZH), session)

        assertEquals(DirectoryTranslationResult(), result)
        assertEquals(mapOf("/root/.Rain.WAV" to ".雨.WAV", "/root/Rain.srt" to "雨.srt", "/root/Rain.v2" to "雨.v2"), translatedNames)
        assertEquals(listOf("Rain" to ENGLISH, "Rain.v2" to ENGLISH), session.requests)
        assertTrue(session.identifications.isEmpty())
    }

    @Test fun explicitPairChecksBothModelsBeforeInferences() = runBlocking {
        val session = FakeSession()
        val result = preview(listOf(entry("雨.wav")), PageTranslationOptions(JAPANESE, CHINESE), emptySet(), session)

        assertEquals(setOf(JA_EN, EN_ZH), result.missingRequiredModels)
        assertTrue(session.requests.isEmpty())
        assertTrue(translatedNames.isEmpty())
    }

    @Test fun autoChecksAllRequiredModelsBeforeDetectingOrDisplayingNames() = runBlocking {
        val session = FakeSession(identify = { text ->
            when (text) {
                "Dogs" -> ENGLISH
                "こんばんは" -> JAPANESE
                "你好" -> CHINESE
                else -> null
            }
        })
        val files = listOf(entry("Dogs.wav"), entry("こんばんは.srt"), entry("你好.txt"), entry("bonjour.flac"))
        val missing = preview(files, PageTranslationOptions(), setOf(EN_ZH), session)

        assertEquals(setOf(JA_EN), missing.missingRequiredModels)
        assertTrue(translatedNames.isEmpty())
        assertTrue(session.identifications.isEmpty())
        assertTrue(session.requests.isEmpty())

        val ready = preview(files, PageTranslationOptions(), setOf(EN_ZH, JA_EN), session)
        assertEquals(DirectoryTranslationResult(), ready)
        assertEquals(mapOf("/root/Dogs.wav" to "译名.wav", "/root/こんばんは.srt" to "译名.srt"), translatedNames)
        assertEquals(listOf("Dogs" to ENGLISH, "こんばんは" to JAPANESE), session.requests)
        assertEquals(listOf("Dogs", "こんばんは", "你好", "bonjour"), session.identifications)

        translatedNames.clear()
        val afterDelete = preview(files, PageTranslationOptions(), setOf(EN_ZH), session)
        assertEquals(setOf(JA_EN), afterDelete.missingRequiredModels)
        assertTrue(translatedNames.isEmpty())
        assertEquals(listOf("Dogs" to ENGLISH, "こんばんは" to JAPANESE), session.requests)
    }

    @Test fun additionalDetectedLanguagesOnlyNeedTheirOwnMissingPacksAndDoNotBlockOtherNames() = runBlocking {
        val files = listOf(entry("Good night.wav"), entry("Bonsoir.wav"), entry("La pluie.wav"), entry("Saluton.wav"), entry("AB.wav"))
        val session = FakeSession(identify = { text ->
            when (text) {
                "Good night" -> ENGLISH
                "Bonsoir", "La pluie" -> FRENCH
                "Saluton" -> PageTranslationLanguage("eo")
                else -> null
            }
        })
        val options = PageTranslationOptions()
        val defaults = setOf(JA_EN, EN_ZH)

        val partial = preview(files, options, defaults, session)

        assertEquals(DirectoryTranslationResult(missingDetectedModels = setOf(FR_EN)), partial)
        assertEquals(mapOf("/root/Good night.wav" to "译名.wav"), translatedNames)
        assertEquals(1, session.modelChecks.count { it == setOf(FR_EN, EN_ZH) })

        translatedNames.clear()
        val complete = preview(files, options, defaults + FR_EN, session)

        assertEquals(DirectoryTranslationResult(), complete)
        assertEquals(setOf("/root/Good night.wav", "/root/Bonsoir.wav", "/root/La pluie.wav"), translatedNames.keys)
        assertEquals(listOf("Good night" to ENGLISH, "Bonsoir" to FRENCH, "La pluie" to FRENCH), session.requests)
        assertEquals(listOf("Good night", "Bonsoir", "La pluie", "Saluton", "AB"), session.identifications)

        translatedNames.clear()
        val deleted = preview(files, options, defaults, session)

        assertEquals(partial, deleted)
        assertEquals(setOf("/root/Good night.wav"), translatedNames.keys)
    }

    @Test fun cacheIsScopedToLanguageOptionsAndSharedAcrossDirectoryPaths() = runBlocking {
        val session = FakeSession(identify = { ENGLISH })
        val name = entry("Rain.mp3")
        val models = setOf(EN_ZH, EN_JA, JA_EN)
        preview(listOf(name), PageTranslationOptions(), models, session)
        preview(listOf(name.copy(path = "/other/Rain.mp3")), PageTranslationOptions(), models, session)
        preview(listOf(name), PageTranslationOptions(ENGLISH, CHINESE), models, session)
        preview(listOf(name), PageTranslationOptions(ENGLISH, JAPANESE), models, session)

        assertEquals(3, session.requests.size)
        assertEquals(listOf("Rain"), session.identifications)
        assertEquals("译名.mp3", translatedNames["/other/Rain.mp3"])
    }

    @Test fun failedTranslationLeavesOriginalAndCanBeRetried() = runBlocking {
        val files = listOf(entry("A.wav"), entry("B.wav"))
        val options = PageTranslationOptions(ENGLISH, CHINESE)
        val failure = FakeSession(translate = { text, _ -> if (text == "A") error("unavailable") else "乙" })
        val partial = preview(files, options, setOf(EN_ZH), failure)

        assertTrue(partial.hasFailures)
        assertEquals(mapOf("/root/B.wav" to "乙.wav"), translatedNames)

        val retry = FakeSession()
        val completed = preview(files, options, setOf(EN_ZH), retry)
        assertFalse(completed.hasFailures)
        assertEquals(listOf("A" to ENGLISH), retry.requests)
        assertEquals("译名.wav", translatedNames["/root/A.wav"])
    }

    @Test fun directTranslationRequiresOnlyItsDirectionAndSameLanguageSkipsWork() = runBlocking {
        val session = FakeSession()
        val files = listOf(entry("こんばんは.wav"))
        assertEquals(DirectoryTranslationResult(), preview(files, PageTranslationOptions(JAPANESE, JAPANESE), emptySet(), session))
        assertTrue(session.requests.isEmpty())

        assertEquals(DirectoryTranslationResult(), preview(files, PageTranslationOptions(JAPANESE, ENGLISH), setOf(JA_EN), session))
        assertEquals(listOf("こんばんは" to JAPANESE), session.requests)
    }

    @Test fun autoModeKeepsNamesAlreadyInTargetLanguageAfterPreflight() = runBlocking {
        val session = FakeSession(identify = { CHINESE })
        val result = preview(listOf(entry("文件.wav")), PageTranslationOptions(), setOf(JA_EN, EN_ZH), session)

        assertEquals(DirectoryTranslationResult(), result)
        assertEquals(listOf("文件"), session.identifications)
        assertTrue(session.requests.isEmpty())
        assertTrue(translatedNames.isEmpty())
    }

    @Test fun changingSystemLanguageDoesNotReuseAnAutomaticTranslation() = runBlocking {
        val frenchPlan = TEST_TRANSLATION_CATALOG.plan(PageTranslationOptions(), Locale.FRANCE)
        val englishPlan = TEST_TRANSLATION_CATALOG.plan(PageTranslationOptions(), Locale.ENGLISH)
        val files = listOf(entry("Résumé.txt"))
        val frenchSession = FakeSession(identify = { FRENCH }, downloadedModels = frenchPlan.requiredModels)
        val englishSession = FakeSession(identify = { ENGLISH }, downloadedModels = englishPlan.requiredModels)

        previewer.preview(files, frenchPlan, frenchSession) { _, _ -> }
        previewer.preview(files, englishPlan, englishSession) { _, _ -> }

        assertEquals(listOf("Résumé" to FRENCH), frenchSession.requests)
        assertEquals(listOf("Résumé" to ENGLISH), englishSession.requests)
    }

    @Test fun cacheEvictsOlderTitles() = runBlocking {
        val session = FakeSession(downloadedModels = setOf(EN_ZH))
        DirectoryNamePreviewer(cacheSize = 1).preview(
            entries = listOf(entry("Rain.mp3"), entry("Wind.wav"), entry("Rain.srt")),
            plan = TEST_TRANSLATION_CATALOG.plan(PageTranslationOptions(ENGLISH, CHINESE), Locale.ENGLISH),
            session = session,
            onTranslated = { _, _ -> },
        )
        assertEquals(listOf("Rain" to ENGLISH, "Wind" to ENGLISH, "Rain" to ENGLISH), session.requests)
    }

    @Test fun cancellationDoesNotPublishPreview() = runBlocking {
        val session = FakeSession(translate = { _, _ -> throw CancellationException() })
        try {
            preview(listOf(entry("Dogs.mp3")), PageTranslationOptions(ENGLISH, CHINESE), setOf(EN_ZH), session)
            fail("Cancellation must be propagated")
        } catch (_: CancellationException) {
            assertTrue(translatedNames.isEmpty())
        }
    }

    private fun entry(name: String) = DirectoryTranslationEntry("/root/$name", name, false)

    private suspend fun preview(
        files: List<DirectoryTranslationEntry>,
        options: PageTranslationOptions,
        models: Set<PageTranslationModel>,
        session: FakeSession,
    ): DirectoryTranslationResult {
        val plan = TEST_TRANSLATION_CATALOG.plan(options, Locale.ENGLISH)
        session.downloadedModels = models
        return previewer.preview(files, plan, session) { path, name -> translatedNames[path] = name }
    }

    private class FakeSession(
        private val identify: (String) -> PageTranslationLanguage? = { null },
        private val translate: (String, PageTranslationLanguage) -> String = { _, _ -> "译名" },
        var downloadedModels: Set<PageTranslationModel> = emptySet(),
    ) : PageTranslationSession {
        val identifications = mutableListOf<String>()
        val requests = mutableListOf<Pair<String, PageTranslationLanguage>>()
        val modelChecks = mutableListOf<Set<PageTranslationModel>>()

        override suspend fun missingModels(models: Collection<PageTranslationModel>): Set<PageTranslationModel> {
            val directions = models.toSet()
            modelChecks += directions
            return directions - downloadedModels
        }

        override suspend fun identifyLanguage(text: String): PageTranslationLanguage? {
            identifications += text
            return identify(text)
        }

        override suspend fun translate(text: String, source: PageTranslationLanguage): String {
            requests += text to source
            return translate.invoke(text, source)
        }
    }
}
