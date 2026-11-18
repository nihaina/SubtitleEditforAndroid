package com.subtitleedit.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertMetadataField
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.MediaTagEditorViewModel
import com.subtitleedit.R
import com.subtitleedit.feature.ui.MainFilePreviewLoader
import com.subtitleedit.player.PlayerMediaNotificationMetadata
import com.subtitleedit.player.PlayerTrack
import com.subtitleedit.util.MediaFilePropertiesReader
import com.subtitleedit.util.SharedFileCache
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaReadPolicyTest {
    @Test fun ordinaryFilesShowCoversAndReadTemporaryTagsOnInfoOrEditingWithoutJoiningTheLibrary() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(app.cacheDir, "media-read-policy-${System.nanoTime()}").apply { check(mkdirs()) }
        val file = File(root, "ordinary file.wav")
        val repository = MediaLibraryRepository.getInstance(app)
        var editor: MediaTagEditorViewModel? = null
        try {
            val count = 8_000
            file.writeBytes(ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(count); putInt(count * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(count * 2)
            }.array())
            val image = File(root, "cover.jpg")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            } finally { bitmap.recycle() }
            MediaTagAudioRepository.write(file, MediaConvertMetadata(title = "Embedded title", artist = "Embedded artist",
                cover = MediaConvertCoverMode.CUSTOM, coverPath = image.path,
                editedFields = setOf(MediaConvertMetadataField.TITLE, MediaConvertMetadataField.ARTIST)))
            val metadata = requireNotNull(SharedFileCache.attributes.refresh(file))

            val preview = MainFilePreviewLoader.preview(app, metadata, 128)
            assertBlueCover("Ordinary folder previews show the embedded cover", preview.bitmap)
            assertBlueCover("A cached folder preview keeps the cover immediately available",
                MainFilePreviewLoader.peek(metadata, 128)?.bitmap)
            assertTrue(preview.mediaDuration.isNotBlank())
            assertNull(repository.getStoredTrack(file.path))
            val information = PlayerTrackMetadataReader.read(file, cacheDirectory = app.cacheDir)
            assertEquals("Embedded title", information.title)
            assertEquals("Embedded artist", information.artist)
            assertTrue(information.tagsAvailable)
            assertBlueCover("Ordinary information views temporarily read tags and cover", information.coverBitmap)
            val properties = MediaFilePropertiesReader.read(file, readTags = true, cacheDirectory = app.cacheDir)
                .mediaDetails.associate { it.label to it.value }
            assertEquals("Embedded title", properties[R.string.media_convert_info_title])
            assertEquals("Embedded artist", properties[R.string.media_convert_info_artist])
            val playbackDetails = information.technicalDetails.associate { it.label to it.value }
            assertEquals("16 bit", playbackDetails[R.string.media_details_bit_depth])
            playbackDetails.forEach { (label, value) -> assertEquals(value, properties[label]) }
            assertNull("Viewing information must not add a collection record", repository.getStoredTrack(file.path))
            val notification = PlayerMediaNotificationMetadata.load(app, PlayerTrack(file.path, file.name, false), 1_000L)
            assertEquals("ordinary file", notification.title)
            assertEquals("", notification.artist)
            assertEquals("", notification.album)
            assertBlueCover("Ordinary playback notifications show the embedded cover", notification.artwork)
            assertFalse(repository.isIndexedTrack(file.path))

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                editor = MediaTagEditorViewModel(app).also { it.initialize(listOf(file.path)) }
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (requireNotNull(editor).state.value.selection.name == "READING" && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(20L)
            }
            assertEquals("Embedded title", requireNotNull(editor).state.value.metadata.title)
            assertEquals("Embedded artist", requireNotNull(editor).state.value.metadata.artist)
            assertTrue(requireNotNull(editor).state.value.hasSourceCover)
            assertFalse("Opening the editor must not add the file to the catalog", repository.isIndexedTrack(file.path))
            assertBlueCover("The file cover remains available after editing",
                MainFilePreviewLoader.preview(app, metadata, 128).bitmap)
            val afterEditor = PlayerTrackMetadataReader.read(file, cacheDirectory = app.cacheDir)
            assertEquals("Embedded title", afterEditor.title)
            assertEquals("Embedded artist", afterEditor.artist)
            assertTrue(afterEditor.tagsAvailable)
            assertNull("Temporary tag reads must not persist library membership", repository.getStoredTrack(file.path))
            val afterEditorNotification = PlayerMediaNotificationMetadata.load(app,
                PlayerTrack(file.path, file.name, false), 1_000L)
            assertEquals("ordinary file", afterEditorNotification.title)
            assertEquals("", afterEditorNotification.artist)
            assertBlueCover("Editor tag caches do not affect independent cover loading", afterEditorNotification.artwork)

            repository.scanDirectoriesToLibrary(listOf(root))
            val libraryPreview = MainFilePreviewLoader.preview(app, metadata, 128)
            assertBlueCover("Library scanning preserves the existing cover display", libraryPreview.bitmap)
            assertEquals("Embedded title", repository.getStoredTrack(file.path)?.title)
            assertEquals("Embedded artist", PlayerMediaNotificationMetadata.load(app,
                PlayerTrack(file.path, file.name, false), 1_000L).artist)
        } finally {
            editor?.cancel()
            repository.removeDirectoryFromLibrary(root, emptyList())
            root.walkTopDown().filter { it.isFile }.forEach { PlayerTrackMetadataReader.invalidate(it.path) }
            root.deleteRecursively()
        }
    }

    private fun assertBlueCover(message: String, bitmap: Bitmap?) {
        assertNotNull(message, bitmap)
        val cover = requireNotNull(bitmap)
        val pixel = cover.getPixel(cover.width / 2, cover.height / 2)
        assertTrue(message, Color.blue(pixel) > 200 && Color.red(pixel) < 40 && Color.green(pixel) < 40)
    }
}
