package com.subtitleedit.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.subtitleedit.translation.PageTranslationLanguage.Companion.CHINESE
import com.subtitleedit.translation.PageTranslationLanguage.Companion.ENGLISH
import com.subtitleedit.translation.PageTranslationLanguage.Companion.JAPANESE
import com.subtitleedit.work.PageTranslationDownloadScheduler
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
