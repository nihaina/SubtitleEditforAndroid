package com.subtitleedit

import android.app.Application
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.util.MediaFilePropertiesReader
import com.subtitleedit.util.FfprobeMediaInformation
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTagNativeWorkflowTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun tagAwareReadWriteRoundTripsAllCommonFieldsAcrossAudioFormats() = withRoot { root ->
        val cover = File(root, "cover.jpg")
        ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=c=blue:s=64x64", "-frames:v", "1", cover.path))
        for ((extension, codec) in listOf("wav" to "pcm_s24le", "mp3" to "libmp3lame",
            "flac" to "flac", "ogg" to "libvorbis", "opus" to "libopus", "m4a" to "aac",
            "wma" to "wmav2", "wv" to "wavpack", "aiff" to "pcm_s16be")) {
            val file = File(root, "source.$extension")
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "0.3",
                "-c:a", codec, "-metadata", "title=Original", "-metadata", "artist=Singer", file.path))
            val hash = audioHash(file, root)
            val expected = completeMetadata()
            val saved = MediaTagAudioRepository.write(file, expected)
            for (field in MediaConvertMetadata.audioFields) assertEquals("$extension $field",
                expected.value(field), saved.metadata.value(field))
            assertEquals(extension, hash, audioHash(file, root))
            val properties = MediaFilePropertiesReader.read(file).mediaDetails.associate { it.label to it.value }
            assertEquals(extension, expected.title, properties["歌曲名"])
            assertEquals(extension, expected.artist, properties["艺术家"])
            assertEquals(extension, expected.album, properties["专辑"])
            val cleared = MediaConvertMetadata.audioFields.fold(MediaConvertMetadata()) { value, field -> value.withValue(field, "") }
            val blank = MediaTagAudioRepository.write(file, cleared)
            for (field in MediaConvertMetadata.audioFields) assertEquals("$extension $field", "", blank.metadata.value(field))
            MediaTagAudioRepository.write(file, MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = cover.path))
            assertArrayEquals(extension, cover.readBytes(), MediaTagAudioRepository.pictures(file).first().data)
            MediaTagAudioRepository.write(file, MediaConvertMetadata(cover = MediaConvertCoverMode.NONE))
            assertTrue(extension, MediaTagAudioRepository.pictures(file).isEmpty())
            assertEquals(extension, hash, audioHash(file, root))
        }
    }

    @Test fun nativeUpdatesKeepMultipleLyricsAndDefaultArtThenReplaceAndRemoveCovers() = withRoot { root ->
        val cover = File(root, "cover.jpg")
        ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=c=blue:s=64x64", "-frames:v", "1", cover.path))
        for (extension in listOf("wav", "mp3")) {
            val file = File(root, "lyrics.$extension")
            ffmpeg(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.3", file.path))
            val lyrics = listOf(MediaConvertLyric("原文 🌊", "[00:00.00]原文 🌊\n"),
                MediaConvertLyric("Translation", "[00:00.00]Sea breeze\n"))
            if (extension == "wav") MediaConvertId3Lyrics.writeWav(file, lyrics) else MediaConvertId3Lyrics.writeMp3(file, lyrics)
            val original = file.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read)
            val hash = audioHash(file, root)
            MediaTagAudioRepository.write(file, completeMetadata())
            val after = file.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read)
            assertEquals(extension, original.map { Triple(it.title, it.language, it.text) }.toSet(),
                after.map { Triple(it.title, it.language, it.text) }.toSet())
            assertEquals(0, MediaTagAudioRepository.pictures(file).size)
            MediaTagAudioRepository.write(file, MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = cover.path))
            val pictures = MediaTagAudioRepository.pictures(file)
            assertTrue(extension, pictures.isNotEmpty())
            assertArrayEquals(cover.readBytes(), pictures.first().data)
            MediaTagAudioRepository.write(file, MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "Another title"))
            assertArrayEquals(cover.readBytes(), MediaTagAudioRepository.pictures(file).first().data)
            MediaTagAudioRepository.write(file, MediaConvertMetadata(cover = MediaConvertCoverMode.NONE))
            assertTrue(MediaTagAudioRepository.pictures(file).isEmpty())
            assertEquals(extension, hash, audioHash(file, root))
            assertEquals(original.map { Triple(it.title, it.language, it.text) }.toSet(),
                file.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read)
                    .map { Triple(it.title, it.language, it.text) }.toSet())
        }
    }

    @Test fun publicWavEditorPersistsReadbackAndBatchEditsPreserveDifferentAlbums() {
        @Suppress("DEPRECATION") val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assumeTrue(downloads.isDirectory && downloads.canWrite())
        withRoot(downloads) { root ->
            val sources = listOf("One", "Two").map { name ->
                File(root, "$name.wav").also { file ->
                    ffmpeg(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.2",
                        "-metadata", "album=$name album", file.path))
                }
            }
            withEditor(sources) { editor ->
                instrumentation.runOnMainSync {
                    editor.setMetadata(editor.state.value.metadata.withValue(MediaConvertMetadataField.ARTIST, "共同歌手 🌊"))
                    editor.save()
                }
                await { !editor.state.value.isSaving }
                assertFalse(editor.state.value.message, "失败" in editor.state.value.message)
            }
            for ((index, file) in sources.withIndex()) {
                val saved = requireNotNull(MediaTagAudioRepository.read(file))
                assertEquals("共同歌手 🌊", saved.metadata.artist)
                assertEquals(listOf("One album", "Two album")[index], saved.metadata.album)
                withEditor(listOf(file)) { editor -> assertEquals(saved.metadata.artist, editor.state.value.metadata.artist) }
            }
        }
    }

    @Test fun nativeCallsBorrowDescriptorsAndUnsupportedMediaIsNotModified() = withRoot { root ->
        val file = File(root, "unsupported.bin").apply { writeText("not an audio file") }
        val original = file.readBytes()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            repeat(10) { assertNull(MediaTagNative.read(descriptor.fd)) }
            assertFalse(MediaTagNative.write(descriptor.fd, arrayOf("TITLE"), arrayOf("new"), 0, null, ""))
            assertTrue(descriptor.fileDescriptor.valid())
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun videoTagsKeepTheExistingSubtitleAndAudioStreams() = withRoot { root ->
        val subtitles = File(root, "source.srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:00,150\nFirst line\n\n2\n00:00:00,200 --> 00:00:00,350\nLast line\n\n")
        }
        val file = File(root, "source.mkv")
        ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=size=64x64:rate=10",
            "-f", "lavfi", "-i", "sine", "-i", subtitles.path, "-t", "0.4",
            "-map", "0:v", "-map", "1:a", "-map", "2:s", "-c:v", "libx264", "-c:a", "aac", "-c:s", "srt", file.path))
        val hash = audioHash(file, root)
        withEditor(listOf(file)) { editor ->
            assertEquals(MediaConvertMetadataSelection.SINGLE_VIDEO, editor.state.value.selection)
            instrumentation.runOnMainSync {
                editor.setMetadata(editor.state.value.metadata.withValue(MediaConvertMetadataField.TITLE, "视频标题")
                    .withValue(MediaConvertMetadataField.AUTHOR, "作者").withValue(MediaConvertMetadataField.COMMENT, "备注"))
                editor.save()
            }
            await { !editor.state.value.isSaving }
            assertEquals(editor.state.value.message, "视频标题", editor.state.value.metadata.title)
            assertEquals("作者", editor.state.value.metadata.author)
            assertEquals("备注", editor.state.value.metadata.comment)
            assertTrue(editor.state.value.metadata.editedFields.isEmpty())
        }
        assertEquals(listOf("video", "audio", "subtitle"), FfprobeMediaInformation.read(file.path, root)
            .getStreams().map { it.getType() })
        val extracted = File(root, "extracted.srt")
        ffmpeg(listOf("-y", "-i", file.path, "-map", "0:s:0", "-c:s", "srt", extracted.path))
        assertTrue(extracted.readText().contains("First line"))
        assertTrue(extracted.readText().contains("Last line"))
        assertEquals(hash, audioHash(file, root))
    }

    @Test fun namedPrivateId3DataSurvivesAnUnrelatedTextEdit() = withRoot { root ->
        val file = File(root, "private.mp3")
        ffmpeg(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.3", file.path))
        MediaConvertId3Lyrics.writeMp3(file, listOf(MediaConvertLyric("Lyrics", "[00:00.00]原文\n")))
        val before = file.readBytes()
        val payloadSize = (6..9).fold(0) { result, index -> (result shl 7) or (before[index].toInt() and 127) }
        val privateBody = "com.subtitleedit.test".toByteArray() + byteArrayOf(0, 7, 9, 0, 8, 1)
        fun sizeBytes(value: Int) = byteArrayOf(((value ushr 21) and 127).toByte(),
            ((value ushr 14) and 127).toByte(), ((value ushr 7) and 127).toByte(), (value and 127).toByte())
        val frame = "PRIV".toByteArray() + (if (before[3].toInt() == 4) sizeBytes(privateBody.size)
            else java.nio.ByteBuffer.allocate(4).putInt(privateBody.size).array()) + byteArrayOf(0, 0) + privateBody
        val header = before.copyOfRange(0, 10).apply { sizeBytes(payloadSize + frame.size).copyInto(this, 6) }
        file.writeBytes(header + before.copyOfRange(10, 10 + payloadSize) + frame + before.copyOfRange(10 + payloadSize, before.size))
        val hash = audioHash(file, root)
        MediaTagAudioRepository.write(file, MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "New title"))
        val after = file.readBytes()
        assertTrue("The unrelated PRIV payload was discarded", after.asList().windowed(privateBody.size).any { it == privateBody.asList() })
        assertEquals(hash, audioHash(file, root))
    }

    @Test fun m4aAcceptsNormalizedTrackAndDiscNumbers() = withRoot { root ->
        val file = File(root, "numbers.m4a")
        ffmpeg(listOf("-y", "-f", "lavfi", "-i", "sine", "-t", "0.2", "-c:a", "aac", file.path))
        withEditor(listOf(file)) { editor ->
            instrumentation.runOnMainSync {
                editor.setMetadata(editor.state.value.metadata
                    .withValue(MediaConvertMetadataField.TRACK_NUMBER, "03/012")
                    .withValue(MediaConvertMetadataField.DISC_NUMBER, "01/02"))
                editor.save()
            }
            await { !editor.state.value.isSaving }
            assertEquals(editor.state.value.message, "3/12", editor.state.value.metadata.trackNumber)
            assertEquals("1/2", editor.state.value.metadata.discNumber)
            assertTrue(editor.state.value.metadata.editedFields.isEmpty())
        }
    }

    @Test fun editsKeepSameDescriptionLyricsInDifferentLanguagesAndBackArtwork() = withRoot { root ->
        val file = File(root, "multiple.mp3")
        val cover = File(root, "front.jpg")
        ffmpeg(listOf("-y", "-f", "lavfi", "-i", "sine", "-t", "0.2", file.path))
        ffmpeg(listOf("-y", "-f", "lavfi", "-i", "color=c=red:s=64x64", "-frames:v", "1", cover.path))
        // The app's lyric writer emits a compact, unpadded tag for this fixture.
        MediaConvertId3Lyrics.writeMp3(file, listOf(MediaConvertLyric("Timed", "[00:00.00]Timed\n")))
        fun appendFrame(id: String, body: ByteArray) {
            val data = file.readBytes()
            val size = (6..9).fold(0) { result, index -> (result shl 7) or (data[index].toInt() and 127) }
            fun syncsafe(value: Int) = byteArrayOf(((value ushr 21) and 127).toByte(),
                ((value ushr 14) and 127).toByte(), ((value ushr 7) and 127).toByte(), (value and 127).toByte())
            val frame = id.toByteArray() + (if (data[3].toInt() == 4) syncsafe(body.size)
                else java.nio.ByteBuffer.allocate(4).putInt(body.size).array()) + byteArrayOf(0, 0) + body
            val header = data.copyOfRange(0, 10).apply { syncsafe(size + frame.size).copyInto(this, 6) }
            file.writeBytes(header + data.copyOfRange(10, size + 10) + frame + data.copyOfRange(size + 10, data.size))
        }
        appendFrame("USLT", byteArrayOf(0) + "engshared".toByteArray() + byteArrayOf(0) + "Alpha".toByteArray())
        appendFrame("USLT", byteArrayOf(0) + "jpnshared".toByteArray() + byteArrayOf(0) + "Beta".toByteArray())
        appendFrame("APIC", byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 4, 0) + cover.readBytes())
        val lyrics = file.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read)
            .map { Triple(it.title, it.language, it.text) }.toSet()
        val hash = audioHash(file, root)
        MediaTagAudioRepository.write(file, MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "Edited"))
        MediaTagAudioRepository.write(file, MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = cover.path))
        val pictures = MediaTagAudioRepository.pictures(file)
        assertEquals(2, pictures.size)
        assertArrayEquals(cover.readBytes(), pictures.single { it.pictureType == "Back Cover" }.data)
        assertArrayEquals(cover.readBytes(), pictures.single { it.pictureType == "Front Cover" }.data)
        assertEquals(lyrics, file.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read)
            .map { Triple(it.title, it.language, it.text) }.toSet())
        assertEquals(hash, audioHash(file, root))
    }

    /** Inputs are copied from test-owned staging; the user's original files are never opened writable. */
    @Test fun suppliedWavAndMp3CopiesSaveAllFieldsThroughTheEditor() {
        val inputPath = InstrumentationRegistry.getArguments().getString("taglibSampleRoot")
        assumeTrue("Optional user-sample staging was not supplied", inputPath != null)
        val input = File(requireNotNull(inputPath))
        val output = File(requireNotNull(app.getExternalFilesDir(null)), "taglib-user-sample-results").apply { mkdirs() }
        for (extension in listOf("wav", "mp3")) {
            val file = File(output, "edited.$extension")
            File(input, "user.$extension").copyTo(file, overwrite = true)
            val hash = audioHash(file, output)
            val beforeLyrics = file.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read)
                .map { Triple(it.title, it.language, it.text) }.toSet()
            withEditor(listOf(file)) { editor ->
                instrumentation.runOnMainSync { editor.setMetadata(completeMetadata()); editor.save() }
                await { !editor.state.value.isSaving }
                assertEquals(editor.state.value.message, completeMetadata().title, editor.state.value.metadata.title)
                assertTrue(editor.state.value.metadata.editedFields.isEmpty())
                // A second save on public storage must use the newly published inode's attributes.
                instrumentation.runOnMainSync {
                    editor.setMetadata(editor.state.value.metadata.withValue(MediaConvertMetadataField.GENRE, "新流派 Test"))
                    editor.save()
                }
                await { !editor.state.value.isSaving }
                assertEquals(editor.state.value.message, "新流派 Test", editor.state.value.metadata.genre)
                assertTrue(editor.state.value.metadata.editedFields.isEmpty())
            }
            val saved = requireNotNull(MediaTagAudioRepository.read(file)).metadata
            for (field in MediaConvertMetadata.audioFields) assertEquals("$extension $field",
                if (field == MediaConvertMetadataField.GENRE) "新流派 Test" else completeMetadata().value(field), saved.value(field))
            assertEquals(extension, hash, audioHash(file, output))
            assertEquals(extension, beforeLyrics, file.inputStream().buffered().use(MediaConvertEmbeddedLyricReader::read)
                .map { Triple(it.title, it.language, it.text) }.toSet())
            withEditor(listOf(file)) { editor -> assertEquals(saved, editor.state.value.metadata) }
        }
    }

    private fun completeMetadata(): MediaConvertMetadata {
        val values = mapOf(MediaConvertMetadataField.TITLE to "曲名 🌊", MediaConvertMetadataField.ARTIST to "歌手",
            MediaConvertMetadataField.ALBUM to "专辑", MediaConvertMetadataField.GENRE to "任意流派",
            MediaConvertMetadataField.YEAR to "2026", MediaConvertMetadataField.ALBUM_ARTIST to "专辑歌手",
            MediaConvertMetadataField.COMPOSER to "作曲者", MediaConvertMetadataField.TRACK_NUMBER to "3/12",
            MediaConvertMetadataField.DISC_NUMBER to "1/2", MediaConvertMetadataField.COMMENT to "第一行\n第二行",
            MediaConvertMetadataField.LYRICIST to "作词者", MediaConvertMetadataField.COPYRIGHT to "版权",
            MediaConvertMetadataField.LANGUAGE to "zho")
        return values.entries.fold(MediaConvertMetadata()) { metadata, (field, value) -> metadata.withValue(field, value) }
    }

    @Test fun foreignTypedTagsSurviveUnrelatedEdits() {
        val output = File(requireNotNull(app.getExternalFilesDir(null)), "taglib-user-sample-results").apply { mkdirs() }
        for (extension in listOf("wma", "m4a")) {
            val file = File(output, "preservation.$extension")
            instrumentation.context.assets.open("media-tags/${file.name}").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val hash = audioHash(file, output)
            val original = requireNotNull(MediaTagAudioRepository.read(file)).properties
            val saved = MediaTagAudioRepository.write(file, MediaConvertMetadata()
                .withValue(MediaConvertMetadataField.TITLE, "Preservation edit"))
            assertEquals(original.filterKeys { it != "TITLE" }, saved.properties.filterKeys { it != "TITLE" })
            assertEquals(hash, audioHash(file, output))
            if (extension == "m4a") {
                val payload = byteArrayOf(0, -1, -111, 0, 16) + "Binary".toByteArray() + byteArrayOf(0)
                assertTrue("The binary freeform payload was lost",
                    file.readBytes().asList().windowed(payload.size).any { it == payload.asList() })
            } else {
                val bytes = file.readBytes()
                val name = "WM/Lyrics\u0000".toByteArray(Charsets.UTF_16LE)
                val start = bytes.asList().windowed(name.size).indexOfFirst { it == name.asList() }
                assertTrue("The language-indexed lyrics attribute was lost", start >= 12)
                val record = java.nio.ByteBuffer.wrap(bytes, start - 12, 12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                assertEquals("The lyric language index changed", 1, record.short.toInt())
                assertEquals("The lyric stream index changed", 1, record.short.toInt())
            }
            // The host Mutagen verifier also checks raw types, language indexes and binary bytes.
        }
    }

    private fun withEditor(files: List<File>, block: (MediaTagEditorViewModel) -> Unit) {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var editor: MediaTagEditorViewModel
        try {
            instrumentation.runOnMainSync {
                editor = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MediaTagEditorViewModel::class.java]
                editor.initialize(files.map { it.path })
            }
            await { editor.state.value.selection != MediaConvertMetadataSelection.READING }
            assertTrue(editor.state.value.selection.editable)
            block(editor)
        } finally { instrumentation.runOnMainSync { owner.viewModelStore.clear() } }
    }

    private fun withRoot(directory: File = app.cacheDir, block: (File) -> Unit) {
        val root = File(directory, "taglib-test-${System.nanoTime()}").apply { mkdirs() }
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("Timed out waiting for tag editor", predicate())
    }

    private fun ffmpeg(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        assertTrue(session.getAllLogsAsString().takeLast(2000), ReturnCode.isSuccess(session.getReturnCode()))
    }

    private fun audioHash(file: File, root: File): String {
        val output = File(root, "audio-hash.txt")
        ffmpeg(listOf("-y", "-i", file.path, "-map", "0:a:0", "-c", "copy", "-f", "hash", "-hash", "sha256", output.path))
        return output.readText().trim()
    }
}
