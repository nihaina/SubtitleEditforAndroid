package com.subtitleedit.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertMetadataField
import com.subtitleedit.MediaTagAudioRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class PlayerTrackMetadataReaderTest {
    @Test fun videoDetailsReadCopyrightAndLanguageAfterPlainLibraryIndexing() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val file = File(app.cacheDir, "library-video-tags-${System.nanoTime()}.mkv")
        try {
            val generated = FFmpegKit.executeWithArguments(arrayOf(
                "-y", "-f", "lavfi", "-i", "color=c=black:s=32x32:r=1:d=1",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-map", "0:v", "-map", "1:a", "-c:v", "mpeg4", "-c:a", "aac",
                "-metadata", "title=Video details", "-metadata", "copyright=Copyright library test",
                "-metadata:s:v:0", "language=jpn", "-metadata:s:a:0", "language=eng", file.path
            ))
            assertTrue(generated.getAllLogsAsString().takeLast(2_000), ReturnCode.isSuccess(generated.getReturnCode()))
            // A plain cached scan must not prevent the details-only probe from loading more tags.
            assertTrue(PlayerTrackMetadataReader.readLibraryTrack(file).isVideo)
            val details = PlayerTrackMetadataReader.read(file, cacheDirectory = app.cacheDir)
            assertEquals("Video details", details.title)
            assertEquals("Copyright library test", details.copyright)
            assertEquals("jpn", details.language)
            assertEquals(details.copyright, PlayerTrackMetadataReader.read(file, cacheDirectory = app.cacheDir).copyright)
        } finally {
            PlayerTrackMetadataReader.invalidate(file.path)
            file.delete()
        }
    }

    @Test fun unavailableExtendedVideoTagsDoNotPreventBasicFileDetails() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val file = File(app.cacheDir, "library-unreadable-video-${System.nanoTime()}.mp4")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            val details = PlayerTrackMetadataReader.read(file, 2_500L, app.cacheDir)
            assertTrue(details.isVideo)
            assertEquals(file.path, details.filePath)
            assertEquals("00:02", details.durationText)
            assertTrue(details.copyright.isBlank())
            assertTrue(details.language.isBlank())
        } finally {
            PlayerTrackMetadataReader.invalidate(file.path)
            file.delete()
        }
    }

    @Test fun textOnlyTagsAndInfoRefreshAfterEditingTheSameFile() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val file = File(app.cacheDir, "library-tags-${System.nanoTime()}.wav")
        val count = 8_000
        file.writeBytes(ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(count); putInt(count * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
        }.array())
        try {
            fun setTitle(title: String) = MediaTagAudioRepository.write(file,
                MediaConvertMetadata(title = title, artist = "Library artist",
                    editedFields = setOf(MediaConvertMetadataField.TITLE, MediaConvertMetadataField.ARTIST)))
            setTitle("Before edit")
            val before = PlayerTrackMetadataReader.readLibraryTrack(file)
            assertEquals("Before edit", before.title)
            assertEquals("Library artist", before.artist)
            assertTrue(before.durationMs in 990L..1010L)
            assertEquals("Before edit", PlayerTrackMetadataReader.read(file).title)
            setTitle("After edit")
            PlayerTrackMetadataReader.invalidate(file.path)
            assertEquals("After edit", PlayerTrackMetadataReader.readLibraryTrack(file).title)
            assertEquals("After edit", PlayerTrackMetadataReader.read(file).title)
            assertNull(MediaArtworkLoader.loadCover(file.path))
        } finally {
            PlayerTrackMetadataReader.invalidate(file.path)
            file.delete()
        }
    }
}
