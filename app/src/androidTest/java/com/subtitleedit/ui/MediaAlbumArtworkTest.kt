package com.subtitleedit.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaArtworkLoader
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.library.buildMediaAlbums
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real embedded covers are checked through the rendered cells, rather than mocked loaders. */
@RunWith(AndroidJUnit4::class)
class MediaAlbumArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixtureDirectory: File? = null

    @After fun removeFixtures() {
        fixtureDirectory?.listFiles()?.forEach { PlayerTrackMetadataReader.invalidate(it.path) }
        fixtureDirectory?.deleteRecursively()
    }

    @Test fun firstAvailableCoverFollowsAppliedSortAcrossGridListAndDetails() {
        val root = fixtureRoot()
        val video = createVideo(root)
        val blank = createWave(root, "B no cover.wav")
        val red = createWave(root, "C red.wav").also { attachCover(it, AndroidColor.RED, root) }
        val blue = createWave(root, "D blue.wav").also { attachCover(it, AndroidColor.BLUE, root) }
        val albums = buildMediaAlbums(listOf(video, blank, red, blue).mapIndexed { index, file ->
            MediaAlbumTrack(
                track = MediaLibraryTrack(file.path, file.name, album = "Fixture album", isVideo = file == video),
                // The original playback order deliberately differs from name order.
                trackNumber = 4 - index
            )
        })
        val options = mutableStateOf(MediaAlbumViewOptions(sortField = AlbumSortField.NAME, ascending = true))
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaAlbumPageContent(
                        albums = albums,
                        options = options.value,
                        onOptionsChanged = { options.value = it },
                        onPlay = { _, _ -> }
                    )
                }
            }
        }
        // The video and plain audio have no embedded cover and must both be skipped.
        waitForArtwork(Color.Red)
        compose.runOnIdle { options.value = options.value.copy(ascending = false) }
        waitForArtwork(Color.Blue)

        compose.runOnIdle { options.value = options.value.copy(viewMode = AlbumViewMode.LIST_ART) }
        waitForArtwork(Color.Blue)
        compose.onNodeWithTag("album-item:fixture album").performClick()
        waitForArtwork(Color.Blue)
    }

    @Test fun albumCoverPolicyNeverReusesCachedVideoFrames() {
        val video = createVideo(fixtureRoot())
        // Existing player and media-library thumbnails keep their video-frame fallback.
        assertNotNull(MediaArtworkLoader.loadCover(video.path, 64))
        assertNull(MediaArtworkLoader.loadCover(video.path, 64, allowVideoFrame = false))
        assertNotNull(MediaArtworkLoader.loadCover(video.path, 64))
        assertNull(MediaArtworkLoader.loadCover(video.path, 64, allowVideoFrame = false))
    }

    private fun waitForArtwork(expected: Color) {
        compose.waitUntil(5_000L) {
            val pixels = compose.onNodeWithTag("album-artwork", useUnmergedTree = true).captureToImage().toPixelMap()
            val actual = pixels[pixels.width / 2, pixels.height / 2]
            kotlin.math.abs(actual.red - expected.red) < 0.08f &&
                kotlin.math.abs(actual.green - expected.green) < 0.08f &&
                kotlin.math.abs(actual.blue - expected.blue) < 0.08f
        }
    }

    private fun fixtureRoot(): File = File(compose.activity.cacheDir, "album-artwork-${UUID.randomUUID()}")
        .apply { mkdirs(); fixtureDirectory = this }

    private fun createVideo(root: File): File = File(root, "A video.mp4").also { file ->
        val generated = FFmpegKit.executeWithArguments(arrayOf(
            "-y", "-f", "lavfi", "-i", "color=c=black:s=32x32:r=1:d=1",
            "-c:v", "mpeg4", "-an", file.path
        ))
        assertTrue(generated.getAllLogsAsString().takeLast(2_000), ReturnCode.isSuccess(generated.getReturnCode()))
    }

    private fun createWave(root: File, name: String): File = File(root, name).apply {
        val count = 8_000
        writeBytes(ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(count); putInt(count * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
        }.array())
    }

    private fun attachCover(audio: File, color: Int, root: File) {
        val picture = File(root, "cover-$color.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            picture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        MediaTagAudioRepository.write(audio, MediaConvertMetadata(
            cover = MediaConvertCoverMode.CUSTOM,
            coverPath = picture.path
        ))
        PlayerTrackMetadataReader.invalidate(audio.path)
    }
}
