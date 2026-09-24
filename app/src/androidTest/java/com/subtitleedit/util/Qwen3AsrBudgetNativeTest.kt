package com.subtitleedit.util

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in smoke test. Reads installed ASR settings/models and an official sample staged by adb.
 * Run with -e qwenAsrAudio /data/user/0/com.subtitleedit/files/qwen-budget-test/repeated.wav.
 * Does not change model settings or user media; normal test runs skip this model-sized check.
 */
@RunWith(AndroidJUnit4::class)
class Qwen3AsrBudgetNativeTest {
    @Test
    fun longAudioIsRecognizedInBudgetedWindows() {
        val path = InstrumentationRegistry.getArguments().getString("qwenAsrAudio")
        assumeTrue("Pass qwenAsrAudio to enable the installed-model test", !path.isNullOrBlank())
        val audio = File(requireNotNull(path))
        assertTrue(audio.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager.getInstance(context)
        val decoder = Uri.parse(settings.getQwen3AsrDecoderPath())
        val cacheLength = if (decoder.scheme == "content") {
            context.contentResolver.openFileDescriptor(decoder, "r")!!.use {
                Qwen3AsrModelBudget.readCacheLength(File("/proc/self/fd/${it.fd}"))
            }
        } else Qwen3AsrModelBudget.readCacheLength(File(requireNotNull(decoder.path)))
        val chunker = Qwen3AsrChunker(
            Qwen3AsrBudget.fromCacheLength(cacheLength), settings.getSpeechFixedSegmentSeconds()
        )
        val durationMs = Pcm16WavReader(audio).use { it.totalSamples * 1000L / it.sampleRate }
        assertTrue("Use speech longer than the legacy context limit", durationMs > 40_000)
        val recognizer = WhisperRecognizer(
            encoderPath = settings.getQwen3AsrEncoderPath(),
            decoderPath = settings.getQwen3AsrDecoderPath(),
            joinerPath = settings.getQwen3AsrConvFrontendPath(),
            tokensPath = settings.getQwen3AsrTokenizerPath(),
            useVad = false,
            language = "中文",
            contentResolver = context.contentResolver,
            context = context,
            modelType = SettingsManager.ASR_MODEL_QWEN3_ASR,
        )
        val segments = recognizer.recognize(audio, { _, status, _ ->
            Log.i("QwenBudgetTest", status)
        }).getOrThrow()
        assertTrue(segments.size >= 3)
        var end = 0L
        segments.forEach {
            assertTrue(it.text.isNotBlank())
            assertEquals("No empty speech chunk should disappear", end, it.startTime)
            assertTrue(it.endTime > it.startTime)
            assertTrue(it.endTime - it.startTime <= chunker.maxSamples * 1000L / 16_000 + 1)
            end = it.endTime
        }
        assertEquals(durationMs, end)
        Log.i("QwenBudgetTest", "PASS: KV=$cacheLength, durationMs=$durationMs, " +
            "chunks=${segments.size}, maxChunkMs=${chunker.maxSamples * 1000L / 16_000}")
    }
}
