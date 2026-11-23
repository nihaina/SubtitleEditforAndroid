package com.subtitleedit.translation

import com.subtitleedit.translation.PageTranslationLanguage.Companion.CHINESE
import com.subtitleedit.translation.PageTranslationLanguage.Companion.ENGLISH
import com.subtitleedit.translation.PageTranslationLanguage.Companion.JAPANESE
import com.subtitleedit.util.TxtTranslationSession
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TextDocumentPreviewerTest {
    private val previewer = TextDocumentPreviewer()
    private val noWordBoundary: (String, Int) -> Int = { _, _ -> -1 }

    @Test fun preservesAiTextBlocksAndBoundsTheNativeRequests() = runBlocking {
        val source = "  " + "甲".repeat(760) + "\r\n\r\n" + "乙".repeat(39) + "。" + "丙".repeat(100) + "\t"
        val batch = TxtTranslationSession(source, noWordBoundary)
        assertEquals(2, batch.totalCount)
        val expectedBlocks = List(batch.totalCount) {
            batch.nextText.also { batch.appendTranslation("Translation") }
        }
        val session = FakeSession(identifier = { CHINESE }, translator = { _, _ -> "Translation" })

        val result = previewer.preview(source, plan(null, ENGLISH), session, noWordBoundary)

        assertEquals(expectedBlocks, session.identified)
        assertTrue(session.requests.size > expectedBlocks.size)
        assertTrue(session.requests.all { (text, _) -> text.codePointCount(0, text.length) <= 96 })
        assertEquals(listOf(setOf(ZH_EN)), session.modelChecks)
        assertTrue(result.text.startsWith("  Translation"))
        assertTrue(result.text.contains("\r\n\r\n"))
        assertTrue(result.text.endsWith("Translation\t"))
    }

    @Test fun missingAndFailedChunksPreserveSourceWhileOtherChunksAreTranslated() = runBlocking {
        val english = "A".repeat(800) + "\r\n"
        val french = "  É" + "e".repeat(799) + "\r\n\r\n"
        val frenchAgain = "É" + "f".repeat(799) + "\n"
        val failing = "B".repeat(800) + "\n"
        val japanese = "  こんばんは！\t"
        val original = english + french + frenchAgain + failing + japanese
        val session = FakeSession(
            installedModels = setOf(EN_ZH, JA_EN),
            identifier = { text ->
                when (text) {
                    french, frenchAgain -> FRENCH
                    japanese -> JAPANESE
                    else -> ENGLISH
                }
            },
            translator = { text, _ ->
                when (text.first()) {
                    'A' -> "译文"
                    'こ' -> "晚上好！"
                    else -> error("model unavailable")
                }
            },
        )

        val result = previewer.preview(original, plan(null, CHINESE), session, noWordBoundary)

        assertTrue(result.text.startsWith("译文"))
        assertTrue(result.text.endsWith("\r\n" + french + frenchAgain + failing + "  晚上好！\t"))
        assertEquals(setOf(FR_EN), result.missingModels)
        assertTrue(result.hasFailures)
        assertEquals(listOf(english, french, frenchAgain, failing, japanese), session.identified)
        assertEquals(setOf('A', 'B', 'こ'), session.requests.map { it.first.first() }.toSet())
        assertEquals(listOf(setOf(EN_ZH), setOf(FR_EN, EN_ZH), setOf(JA_EN, EN_ZH)), session.modelChecks)
    }

    @Test fun unchangedChunksKeepExactTextWithoutIntroducingSpaces() = runBlocking {
        val original = "A".repeat(800) + "Word\t"
        val session = FakeSession(translator = { text, _ -> text.trim() })

        val result = previewer.preview(original, plan(ENGLISH, CHINESE), session, noWordBoundary)

        assertEquals(TextDocumentTranslationResult(original), result)
        assertTrue(session.requests.size > 2)
    }

    @Test fun failureInOneBoundedRequestKeepsTheRestOfTheBlockTranslatable() = runBlocking {
        val first = "a,".repeat(70)
        val failing = "B".repeat(96)
        val last = "The train reached the station."
        val original = "$first\n\n$failing\n\n$last\t"
        val session = FakeSession(translator = { text, _ ->
            if ('B' in text) error("failed chunk") else "译文"
        })

        val result = previewer.preview(original, plan(ENGLISH, CHINESE), session, noWordBoundary)

        assertTrue(result.hasFailures)
        assertTrue(result.text.startsWith("译文"))
        assertTrue(result.text.endsWith("\n\n$failing\n\n译文\t"))
        assertTrue(session.requests.all { (text, _) -> text.codePointCount(0, text.length) <= 96 })
    }

    @Test fun anExplicitSourceAlreadyInTheTargetLanguageDoesNoInference() = runBlocking {
        val session = FakeSession()

        assertEquals(TextDocumentTranslationResult("文本"),
            previewer.preview("文本", plan(CHINESE, CHINESE), session, noWordBoundary))
        assertTrue(session.modelChecks.isEmpty())
        assertTrue(session.requests.isEmpty())
    }

    @Test fun cancellationIsPropagated() = runBlocking {
        val session = FakeSession(translator = { _, _ -> throw CancellationException() })

        try {
            previewer.preview("A paragraph", plan(ENGLISH, CHINESE), session, noWordBoundary)
            fail("Cancellation must be propagated")
        } catch (_: CancellationException) {
            assertEquals(1, session.requests.size)
        }
    }

    private fun plan(source: PageTranslationLanguage?, target: PageTranslationLanguage) =
        TEST_TRANSLATION_CATALOG.plan(PageTranslationOptions(source, target), Locale.ENGLISH)

    private class FakeSession(
        private val installedModels: Set<PageTranslationModel> = TEST_TRANSLATION_CATALOG.models,
        private val identifier: (String) -> PageTranslationLanguage? = { null },
        private val translator: (String, PageTranslationLanguage) -> String = { _, _ -> "译文" },
    ) : PageTranslationSession {
        val requests = mutableListOf<Pair<String, PageTranslationLanguage>>()
        val modelChecks = mutableListOf<Set<PageTranslationModel>>()
        val identified = mutableListOf<String>()

        override suspend fun missingModels(models: Collection<PageTranslationModel>): Set<PageTranslationModel> {
            val requested = models.toSet()
            modelChecks += requested
            return requested - installedModels
        }

        override suspend fun identifyLanguage(text: String): PageTranslationLanguage? {
            identified += text
            return identifier(text)
        }

        override suspend fun translate(text: String, source: PageTranslationLanguage): String {
            requests += text to source
            return translator(text, source)
        }
    }
}
