package com.subtitleedit.player

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerPlaylistArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var directory: File? = null

    @After fun removeFixtures() {
        directory?.listFiles()?.forEach { PlayerTrackMetadataReader.invalidate(it.path) }
        directory?.deleteRecursively()
    }

    @Test fun embeddedCoverIsShownInsteadOfTheDefaultAudioIcon() {
        val file = createWave(fixtureRoot(), "covered.wav")
        attachCover(file, AndroidColor.RED)
        showArtwork(file)
        waitForCover(file, Color.Red)
        compose.onNodeWithTag("player-playlist-audio-icon:${file.path}").assertDoesNotExist()
    }

    @Test fun changingToCoverlessAudioAndVideoUsesTheCorrectFileIcon() {
        val root = fixtureRoot()
        val covered = createWave(root, "covered.wav").also { attachCover(it, AndroidColor.RED) }
        val coverless = createWave(root, "coverless.wav")
        val video = File(root, "without-cover.mp4")
        val session = FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i",
            "color=c=green:s=64x64:r=1:d=1", "-c:v", "mpeg4", video.path))
        assertTrue(session.getAllLogsAsString(), ReturnCode.isSuccess(session.getReturnCode()))
        val current = showArtwork(covered)
        waitForCover(covered, Color.Red)

        compose.runOnIdle { current.value = PlayerTrack(coverless.path, coverless.name, false) }
        compose.onNodeWithTag("player-playlist-cover:${covered.path}").assertDoesNotExist()
        waitForIcon(coverless, video = false)

        compose.runOnIdle { current.value = PlayerTrack(video.path, video.name, true) }
        waitForIcon(video, video = true)
        // The generated green video frame must not substitute for a missing file cover.
        compose.onNodeWithTag("player-playlist-cover:${video.path}").assertDoesNotExist()
    }

    @Test fun replacingAnEmbeddedCoverRefreshesTheSamePlaylistFile() {
        val file = createWave(fixtureRoot(), "edited.wav")
        attachCover(file, AndroidColor.RED)
        showArtwork(file)
        waitForCover(file, Color.Red)
        attachCover(file, AndroidColor.BLUE)
        waitForCover(file, Color.Blue)
    }

    private fun showArtwork(file: File): MutableState<PlayerTrack> {
        val track = mutableStateOf(PlayerTrack(file.path, file.name, false))
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PlayerPlaylistArtwork(track.value, highlighted = false)
                    }
                }
            }
        }
        return track
    }

    private fun waitForCover(file: File, expected: Color) {
        val tag = "player-playlist-cover:${file.path}"
        compose.waitUntil(5_000L) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) false
            else {
                val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
                val actual = pixels[pixels.width / 2, pixels.height / 2]
                abs(actual.red - expected.red) < 0.08f && abs(actual.green - expected.green) < 0.08f &&
                    abs(actual.blue - expected.blue) < 0.08f
            }
        }
    }

    private fun waitForIcon(file: File, video: Boolean) {
        val tag = "player-playlist-${if (video) "video" else "audio"}-icon:${file.path}"
        compose.waitUntil(5_000L) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun fixtureRoot(): File = File(compose.activity.cacheDir, "playlist-artwork-${UUID.randomUUID()}")
        .apply { mkdirs(); directory = this }

    private fun createWave(root: File, name: String): File = File(root, name).apply {
        val count = 8_000
        writeBytes(ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(count); putInt(count * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
        }.array())
    }

    private fun attachCover(track: File, color: Int) {
        val image = File(track.parentFile, "cover-$color.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        MediaTagAudioRepository.write(track, MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = image.path))
        PlayerTrackMetadataReader.invalidate(track.path)
    }
}
