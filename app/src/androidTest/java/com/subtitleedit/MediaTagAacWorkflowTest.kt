package com.subtitleedit

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTagAacWorkflowTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun aacEditorKeepsNamedTimedLyricsAndPrivateFramesWhenSavingAndClearingTags() {
        val root = File(app.cacheDir, "aac-tag-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val file = File(root, "source.aac")
            val generated = FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i",
                "sine=sample_rate=44100", "-t", "0.3", "-c:a", "aac", "-f", "adts", file.path))
            assertTrue(generated.getAllLogsAsString().takeLast(2000), ReturnCode.isSuccess(generated.getReturnCode()))
            MediaConvertId3Lyrics.writeAac(file, listOf(
                MediaConvertLyric("原文", "[00:00.00]原文 🌊\n[00:01.00]下一行\n"),
                MediaConvertLyric("Translation", "[00:00.00]Original\n[00:01.00]Next\n")))
            addPrivateFrame(file)
            val before = readFramesAndAudio(file)
            val metadata = MediaConvertMetadata.audioFields.fold(MediaConvertMetadata()) { value, field ->
                value.withValue(field, when (field) {
                    MediaConvertMetadataField.TRACK_NUMBER -> "3/12"
                    MediaConvertMetadataField.DISC_NUMBER -> "1/2"
                    MediaConvertMetadataField.YEAR -> "2026"
                    MediaConvertMetadataField.LANGUAGE -> "zho"
                    else -> "${field.name} 中文 🌊"
                })
            }
            withEditor(file) { editor ->
                instrumentation.runOnMainSync { editor.setMetadata(metadata); editor.save() }
                await { !editor.state.value.isSaving }
                assertEquals("", editor.state.value.message)
            }
            val saved = readFramesAndAudio(file)
            assertArrayEquals(before.second, saved.second)
            for (frame in before.first) assertTrue("An untouched AAC ID3 frame was lost",
                saved.first.any { it.contentEquals(frame) })
            withEditor(file) { editor ->
                for (field in MediaConvertMetadata.audioFields) assertEquals(field.name,
                    metadata.value(field), editor.state.value.metadata.value(field))
                val clear = MediaConvertMetadata.audioFields.fold(MediaConvertMetadata()) { value, field -> value.withValue(field, "") }
                instrumentation.runOnMainSync { editor.setMetadata(clear); editor.save() }
                await { !editor.state.value.isSaving }
                assertEquals("", editor.state.value.message)
            }
            val cleared = readFramesAndAudio(file)
            assertArrayEquals(before.second, cleared.second)
            assertEquals(before.first.size, cleared.first.size)
            before.first.zip(cleared.first).forEach { (original, actual) -> assertArrayEquals(original, actual) }
            withEditor(file) { editor ->
                for (field in MediaConvertMetadata.audioFields) assertEquals(field.name, "", editor.state.value.metadata.value(field))
            }
            val cover = File(root, "cover.jpg")
            val generatedCover = FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i",
                "color=c=blue:s=64x64", "-frames:v", "1", cover.path))
            assertTrue(generatedCover.getAllLogsAsString(), ReturnCode.isSuccess(generatedCover.getReturnCode()))
            withEditor(file) { editor ->
                assertTrue(editor.state.value.coverSupported)
                instrumentation.runOnMainSync { editor.selectCover(Uri.fromFile(cover)) }
                await { !editor.state.value.isReadingCover }
                assertEquals(MediaConvertCoverMode.CUSTOM, editor.state.value.metadata.cover)
                instrumentation.runOnMainSync { editor.save() }
                await { !editor.state.value.isSaving }
                assertEquals(editor.state.value.message, true, editor.state.value.hasSourceCover)
                val exported = File(root, "export").apply { mkdirs() }
                instrumentation.runOnMainSync { editor.exportCover(Uri.fromFile(exported)) }
                await { !editor.state.value.isExportingCover }
                assertTrue(editor.state.value.message, exported.listFiles().orEmpty().any { it.extension == "jpg" && it.length() > 0 })
                instrumentation.runOnMainSync {
                    editor.setMetadata(editor.state.value.metadata.copy(cover = MediaConvertCoverMode.NONE))
                    editor.save()
                }
                await { !editor.state.value.isSaving }
                assertFalse(editor.state.value.message, editor.state.value.hasSourceCover)
            }
            assertArrayEquals(before.second, readFramesAndAudio(file).second)
        } finally { root.deleteRecursively() }
    }

    private fun addPrivateFrame(file: File) {
        val data = file.readBytes()
        val size = syncsafe(data, 6)
        val privateData = "com.subtitleedit.aac.test".toByteArray() + byteArrayOf(0, 1, 0, 9, 7, 4)
        val frame = "PRIV".toByteArray() + ByteBuffer.allocate(4).putInt(privateData.size).array() + byteArrayOf(0, 0) + privateData
        val header = data.copyOfRange(0, 10).apply {
            val newSize = size + frame.size
            for (index in 0..3) this[6 + index] = ((newSize ushr (21 - 7 * index)) and 127).toByte()
        }
        file.writeBytes(header + data.copyOfRange(10, 10 + size) + frame + data.copyOfRange(10 + size, data.size))
    }

    /** Independent byte-level check of both the ID3 frames and complete ADTS payload. */
    private fun readFramesAndAudio(file: File): Pair<List<ByteArray>, ByteArray> {
        val data = file.readBytes()
        assertEquals("ID3", String(data, 0, 3))
        assertEquals(3, data[3].toInt())
        val end = 10 + syncsafe(data, 6)
        var cursor = 10
        val frames = mutableListOf<ByteArray>()
        while (cursor < end && data[cursor] != 0.toByte()) {
            val length = ByteBuffer.wrap(data, cursor + 4, 4).int
            frames += data.copyOfRange(cursor, cursor + 10 + length)
            cursor += 10 + length
        }
        assertEquals(end, cursor)
        return frames to data.copyOfRange(end, data.size)
    }

    private fun syncsafe(data: ByteArray, start: Int) = (start until start + 4).fold(0) { result, index ->
        (result shl 7) or (data[index].toInt() and 127)
    }

    private fun withEditor(file: File, block: (MediaTagEditorViewModel) -> Unit) {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var editor: MediaTagEditorViewModel
        try {
            instrumentation.runOnMainSync {
                editor = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MediaTagEditorViewModel::class.java]
                editor.initialize(listOf(file.path))
            }
            await { editor.state.value.selection != MediaConvertMetadataSelection.READING }
            assertTrue(editor.state.value.message, editor.state.value.selection.editable)
            block(editor)
        } finally { instrumentation.runOnMainSync { owner.viewModelStore.clear() } }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("Timed out waiting for the AAC tag editor", predicate())
    }
}
