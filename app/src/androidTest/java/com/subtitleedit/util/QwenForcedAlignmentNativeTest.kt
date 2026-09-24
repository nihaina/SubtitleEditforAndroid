package com.subtitleedit.util

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in checks using real official assets, staged outside the installed model directories.
 * Pass qwenTestDirectory containing tokenizer/ and qwen3_forced_alignment_inputs.json.
 * The ONNX check also requires qwenAlignerModel and the official asr_zh.wav/reference JSON.
 */
@RunWith(AndroidJUnit4::class)
class QwenForcedAlignmentNativeTest {
    private fun testDirectory(): File {
        val path = InstrumentationRegistry.getArguments().getString("qwenTestDirectory")
        assumeTrue("Pass qwenTestDirectory with the official fixtures", !path.isNullOrBlank())
        return File(requireNotNull(path)).also { assertTrue(it.isDirectory) }
    }

    @Test
    fun asrTokenizerMatchesOfficialIdsAndExpandedPositions() {
        val root = testDirectory()
        val directory = File(root, "tokenizer")
        val sourceConfig = File(directory, "tokenizer_config.json").readText()
        val cases = JSONObject(File(root, "qwen3_forced_alignment_inputs.json").readText())
            .getJSONArray("cases")
        QwenHuggingFaceTokenizer.open(directory).use { tokenizer ->
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val encoded = tokenizer.encodeForForcedAlignment(
                    case.getString("text"), case.getString("language")
                )
                val rawIds = case.getJSONArray("raw_ids")
                val rawPositions = case.getJSONArray("raw_timestamp_positions")
                val units = case.getJSONArray("units")
                assertArrayEquals(LongArray(rawIds.length()) { rawIds.getLong(it) }, encoded.inputIds)
                assertArrayEquals(
                    IntArray(rawPositions.length()) { rawPositions.getInt(it) }, encoded.timestampPositions
                )
                assertEquals(List(units.length()) { units.getString(it) }, encoded.units)
                val expanded = Qwen3ForcedAlignmentInput.expand(encoded, case.getInt("frames"))
                val expandedIds = case.getJSONArray("expanded_ids")
                val expandedPositions = case.getJSONArray("expanded_timestamp_positions")
                assertArrayEquals(
                    LongArray(expandedIds.length()) { expandedIds.getLong(it) }, expanded.inputIds
                )
                assertArrayEquals(
                    IntArray(expandedPositions.length()) { expandedPositions.getInt(it) },
                    expanded.timestampPositions
                )
            }
        }
        assertEquals(sourceConfig, File(directory, "tokenizer_config.json").readText())
    }

    @Test
    fun jniReportsInvalidTextAndSafelyClosesLongResults() {
        val tokenizer = QwenHuggingFaceTokenizer.open(File(testDirectory(), "tokenizer"))
        tokenizer.use {
            val error = runCatching { it.encodeForForcedAlignment("...！？", "Chinese") }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("清理标点后为空"))
            val result = it.encodeForForcedAlignment("你".repeat(1024), "Chinese")
            assertEquals(1024, result.units.size)
            assertEquals(2048, result.timestampPositions.size)
        }
        tokenizer.close()
        val closed = runCatching { tokenizer.encodeForForcedAlignment("你好", "Chinese") }.exceptionOrNull()
        assertTrue(closed is IllegalStateException)
    }

    @Test
    fun officialChineseAudioMatchesReferenceTimestamps() {
        val root = testDirectory()
        val modelPath = InstrumentationRegistry.getArguments().getString("qwenAlignerModel")
        assumeTrue("Pass qwenAlignerModel to run the ONNX integration check", !modelPath.isNullOrBlank())
        val reference = JSONObject(File(root, "forced_aligner.audio-validation.json").readText())
        val samples = Pcm16WavReader(File(root, "asr_zh.wav")).use { reader ->
            assertEquals(16_000, reader.sampleRate)
            reader.readRange(0L, reader.totalSamples.toInt())
        }
        val features = QwenLogMelExtractor().extract(samples)
        val encoded = Qwen3ForcedAlignmentTextEncoder(File(root, "tokenizer")).use {
            it.encode(reference.getString("text"), reference.getString("language"), features.first().size)
        }
        val aligned = Qwen3ForcedAlignerOnnx(File(requireNotNull(modelPath))).use { aligner ->
            aligner.align(
                Qwen3ForcedAlignerOnnx.Input(
                    inputIds = encoded.inputIds,
                    inputFeatures = features,
                    attentionMask = LongArray(encoded.inputIds.size) { 1L },
                    featureAttentionMask = LongArray(features.first().size) { 1L },
                    timestampPositions = encoded.timestampPositions,
                    units = encoded.units,
                )
            )
        }
        Log.i("QwenAlignmentTest", "Android alignment: $aligned")
        val expected = reference.getJSONArray("alignment_ms")
        assertEquals(expected.length(), aligned.size)
        aligned.forEachIndexed { index, unit ->
            val timestamp = expected.getJSONObject(index)
            assertEquals(timestamp.getString("text"), unit.text)
            assertEquals("start of ${unit.text}", timestamp.getLong("start_time"), unit.startTimeMs)
            assertEquals("end of ${unit.text}", timestamp.getLong("end_time"), unit.endTimeMs)
        }
    }
}
