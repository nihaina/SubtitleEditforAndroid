package com.subtitleedit.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
