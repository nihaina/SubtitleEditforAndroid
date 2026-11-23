package com.subtitleedit.translation

import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.subtitleedit.util.SubtitleTextSplitOps
import com.subtitleedit.translation.PageTranslationLanguage.Companion.CHINESE
import com.subtitleedit.translation.PageTranslationLanguage.Companion.ENGLISH
import com.subtitleedit.translation.PageTranslationLanguage.Companion.JAPANESE
import com.subtitleedit.work.PageTranslationDownloadScheduler
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoxletPageTranslationDeviceTest {
    @Test fun reusesTextTranslationsAfterReopeningThePreview() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(FoxletPageTranslation.isSupported)
        val cacheRoot = File(context.cacheDir, "translation-cache-test-${System.nanoTime()}")
        val isolated = object : ContextWrapper(context) {
            override fun getCacheDir(): File = cacheRoot
        }
        val source = InstrumentationRegistry.getArguments().getString("foxletCacheTextFile")?.let { File(it).readText() }
            ?: "明日は朝早く家を出て、電車で図書館に行きます。\r\n\r\n夜には友達と一緒に映画を見ます。\r\n"
        val options = PageTranslationOptions(target = CHINESE)
        try {
            val plan = FoxletPageTranslation.catalog(isolated).plan(options, Locale.JAPAN)
            val route = checkNotNull(plan.route(JAPANESE))
            assumeTrue("The Japanese to Chinese route must already be installed",
                FoxletPageTranslation.downloaded(isolated, route.toSet()).containsAll(route))

            suspend fun preview() = FoxletPageTranslation.openPreview(isolated, options, Locale.JAPAN).use { session ->
                TextDocumentPreviewer().preview(source, session.plan, session) { text, offset ->
                    SubtitleTextSplitOps.wordStartAt(context, text, offset)
                }
            }
            fun cacheFiles() = File(cacheRoot, "text_translation").listFiles().orEmpty()
                .associate { it.name to Os.stat(it.absolutePath).st_ino }

            val first = preview()
            assertTrue("Japanese text was translated", first.text != source && containsHan(first.text))
            assertTrue(first.missingModels.isEmpty() && !first.hasFailures)
            val entries = cacheFiles()
            assertTrue("The first preview writes several translations", entries.size > 1)

            assertEquals(first, preview())
            assertEquals("The second preview reuses the disk entries", entries, cacheFiles())
        } finally {
            cacheRoot.deleteRecursively()
        }
        Unit
    }

    @Test fun translatesLongDocumentsWithInstalledDirectAndPivotModels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(FoxletPageTranslation.isSupported)
        val plans = listOf(
            Triple(ENGLISH, "The train passes through the valley early in the morning and continues to the town " +
                "where the passengers have breakfast before visiting the museum ", "The door closed at midnight."),
            Triple(JAPANESE, "今日は天気が良かったので朝から電車に乗って図書館に行き友達と本を借りて公園でお弁当を食べました",
                "最後に駅で友達と別れました。"),
        )
        for ((language, phrase, tail) in plans) {
            FoxletPageTranslation.openPreview(context, PageTranslationOptions(language, CHINESE), Locale.ENGLISH).use { session ->
                assumeTrue("Install the language packs for ${language.tag} → ${CHINESE.tag}",
                    session.missingModels(session.plan.requiredModels).isEmpty())
                val first = phrase.repeat(10).trimEnd()
                val source = "$first\r\n\r\n$tail\r\n"
                val result = TextDocumentPreviewer().preview(source, session.plan, session) { text, offset ->
                    SubtitleTextSplitOps.wordStartAt(context, text, offset)
                }
                assertTrue("${language.tag} preview uses the installed models", result.missingModels.isEmpty() && !result.hasFailures)
                val paragraphs = result.text.split("\r\n\r\n")
                assertEquals(2, paragraphs.size)
                assertTrue("${language.tag} long paragraph translated", paragraphs[0] != first && containsHan(paragraphs[0]))
                assertTrue("${language.tag} final paragraph translated", paragraphs[1].trim() != tail && containsHan(paragraphs[1]))
                assertTrue(result.text.endsWith("\r\n"))
            }
        }
    }

    private fun containsHan(text: String): Boolean =
        text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }

    @Test fun translatesDirectoryNamesWithOfficialModels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "Pass foxletDeviceSmoke=true on a test device with an unmetered connection",
            InstrumentationRegistry.getArguments().getString("foxletDeviceSmoke") == "true",
        )
        assertTrue(FoxletPageTranslation.isSupported)
        val catalog = FoxletPageTranslation.catalog(context)
        val scheduler = PageTranslationDownloadScheduler(context)
        val plans = listOf(
            PageTranslationOptions(ENGLISH, CHINESE) to "This is an English test sentence",
            PageTranslationOptions(JAPANESE, CHINESE) to "今日は天気が良いですね",
        )
        val models = plans.flatMapTo(linkedSetOf()) { (options, _) ->
            catalog.plan(options, Locale.ENGLISH).requiredModels
        }
        val missing = models - FoxletPageTranslation.downloaded(context, models)
        if (missing.isNotEmpty()) {
            missing.forEach { scheduler.enqueue(it) }
            val downloadResults = withTimeout(240_000) {
                scheduler.observe(catalog).first { jobs -> missing.all { jobs[it]?.state?.isFinished == true } }
            }
            missing.forEach { assertEquals(it.id, WorkInfo.State.SUCCEEDED, downloadResults[it]?.state) }
        }
        for ((options, text) in plans) {
            FoxletPageTranslation.openPreview(context, options, Locale.ENGLISH).use { session ->
                assertTrue(session.missingModels(session.plan.requiredModels).isEmpty())
                val nativeTranslation = session.translate(text, checkNotNull(options.source)).trim()
                val entry = DirectoryTranslationEntry("/test/$text.WAV", "$text.WAV", isDirectory = false)
                val names = mutableMapOf<String, String>()
                val result = DirectoryNamePreviewer().preview(listOf(entry), session.plan, session) { path, name ->
                    names[path] = name
                }
                assertEquals(DirectoryTranslationResult(), result)
                val translated = checkNotNull(names[entry.path])
                assertEquals("$nativeTranslation.WAV", translated)
                assertTrue(translated, translated.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
            }
        }
    }
}
