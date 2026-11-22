package com.subtitleedit.work

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.util.SettingsManager
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechToSubtitleTaskStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun largeRequestAndOutputResultsSurviveReopeningTheStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID()
        val store = SpeechToSubtitleTaskStore(context, id)
        val outputDirectory = Uri.fromFile(folder.root)
        val request = SpeechToSubtitleRequest(
            files = List(180) { index ->
                val name = "相同字幕名.媒体文件.$index.mp4"
                SpeechToSubtitleFile(Uri.fromFile(File(folder.root, name)), name, null, outputDirectory)
            },
            language = "中文",
            format = "SRT",
            modelType = SettingsManager.ASR_MODEL_QWEN3_ASR,
            encoderPath = "/models/encoder.onnx",
            decoderPath = "/models/decoder.onnx",
            joinerPath = "/models/conv_frontend.onnx",
            tokensPath = "/models/tokenizer",
            vadModelPath = "",
            forcedAlignerPath = "/models/forced_aligner.onnx",
            useVad = false,
            useTokenTimeline = true,
            saveToSourceDirectory = false,
            outputDirectory = outputDirectory,
            overwriteOutput = false,
            addToAutoTranslate = true,
            disableVadForTxt = false,
        )
        try {
            store.writeRequest(request)
            val reopened = SpeechToSubtitleTaskStore(context, id)
            assertEquals(request, reopened.readRequest())

            val source = request.files.first()
            val baseName = source.fileName.substringBeforeLast('.')
            val existing = File(folder.root, "$baseName.srt").apply { writeText("已有字幕") }
            val output = store.export(0, source, request.format, SpeechToSubtitleContent("识别结果", 1), false)
            store.writeFailure(1, "语音识别失败")
            assertEquals(output, reopened.readOutcome(0)?.output)
            assertEquals("语音识别失败", reopened.readOutcome(1)?.error)
            assertEquals("已有字幕", existing.readText())
            assertEquals("$baseName (1).srt", output.fileName)
            assertEquals("识别结果", File(folder.root, output.fileName).readText())

            assertFalse(reopened.isAcknowledged())
            store.acknowledge()
            assertTrue(reopened.isAcknowledged())
        } finally {
            store.delete()
        }
    }
}
