package com.subtitleedit.player

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

/** Real embedded pictures exercise the same bounded loader used by the audio playback page. */
@RunWith(AndroidJUnit4::class)
class PlayerAudioArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var directory: File? = null

    @After fun removeFixtures() {
        directory?.let { MediaLibraryRepository.getInstance(compose.activity).removeDirectoryFromLibrary(it, emptyList()) }
        directory?.listFiles()?.forEach { PlayerTrackMetadataReader.invalidate(it.path) }
        directory?.deleteRecursively()
    }

    @Test fun embeddedCoverReplacesVinylAndKeepsTheWaveform() {
        val root = fixtureRoot()
        val track = createWave(root, "with-cover.wav")
        attachCover(track, AndroidColor.RED)
        showVisual(track)

        waitForCover(Color.Red)
        compose.onNodeWithTag("player-audio-vinyl").assertDoesNotExist()
        compose.onNodeWithTag("player-loudness-waveform").assertIsDisplayed()
        assertCoverAndWaveformAreSeparated()
    }

    @Test fun landscapeCoverLeavesSpaceAboveTheWaveform() {
        val track = createWave(fixtureRoot(), "landscape-cover.wav")
        attachCover(track, AndroidColor.RED)
        showVisual(track, landscape = true)

        waitForCover(Color.Red)
        compose.onNodeWithTag("player-loudness-waveform").assertIsDisplayed()
        assertCoverAndWaveformAreSeparated()
    }

    @Test fun switchingToCoverlessAudioClearsThePreviousCover() {
        val root = fixtureRoot()
        val covered = createWave(root, "with-cover.wav").also { attachCover(it, AndroidColor.RED) }
        val coverless = createWave(root, "without-cover.wav")
        val current = showVisual(covered)
        waitForCover(Color.Red)

        compose.runOnIdle { current.value = coverless.path }
        compose.onNodeWithTag("player-audio-vinyl").assertIsDisplayed()
        compose.onNodeWithTag("player-audio-cover").assertDoesNotExist()
        compose.onNodeWithTag("player-loudness-waveform").assertIsDisplayed()

        compose.runOnIdle { current.value = covered.path }
        waitForCover(Color.Red)
        compose.onNodeWithTag("player-audio-vinyl").assertDoesNotExist()
    }

    @Test fun returningAfterATagEditRefreshesTheSameTracksCover() {
        val track = createWave(fixtureRoot(), "edited-cover.wav")
        attachCover(track, AndroidColor.RED)
        showVisual(track)
        waitForCover(Color.Red)

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        attachCover(track, AndroidColor.BLUE)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        waitForCover(Color.Blue)
        compose.onNodeWithTag("player-audio-vinyl").assertDoesNotExist()
    }

    @Test fun externalCoverStaysVisibleWhenAddingAndRemovingFavorites() {
        val track = createWave(fixtureRoot(), "membership-cover.wav")
        attachCover(track, AndroidColor.RED, index = false)
        val repository = MediaLibraryRepository.getInstance(compose.activity)
        try {
            showVisual(track)
            waitForCover(Color.Red)
            assertTrue(repository.toggleFavorite(track.path))
            waitForCover(Color.Red)

            repository.removeFavorite(track.path)
            waitForCover(Color.Red)
            compose.onNodeWithTag("player-audio-vinyl").assertDoesNotExist()
            assertTrue(repository.getIndexedTracks().none { it.path == track.path })
        } finally {
            repository.removeFavorite(track.path)
        }
    }

    @Test fun embeddedArtworkTouchBoundsFollowCoverWaveformOrientationAndTransforms() {
        val root = fixtureRoot()
        val covered = createWave(root, "hit-cover.wav").also { attachCover(it, AndroidColor.RED) }
        val coverless = createWave(root, "hit-vinyl.wav")
        val path = mutableStateOf(covered.path)
        val landscape = mutableStateOf(false)
        val waveform = mutableStateOf(true)
        val translation = mutableStateOf(0f)
        val artworkBounds = mutableStateOf<Rect?>(null)
        val origin = mutableStateOf(Offset.Zero)
        var clicks = 0
        val baseConfiguration = Configuration(compose.activity.resources.configuration)
        compose.setContent {
            val configuration = Configuration(baseConfiguration).apply {
                orientation = if (landscape.value) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = if (landscape.value) 720 else 360
                screenHeightDp = if (landscape.value) 360 else 720
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                SubtitleEditComposeTheme {
                    Box(Modifier.fillMaxSize().testTag("embedded-audio")
                        .onGloballyPositioned { origin.value = it.positionInRoot() }) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PlayerAudioVisual(false, 0f, 0L, waveform.value,
                                modifier = Modifier.graphicsLayer { translationY = translation.value }
                                    .padding(bottom = if (landscape.value) 24.dp else 120.dp),
                                trackPath = path.value,
                                onArtworkBoundsChanged = { artworkBounds.value = it })
                        }
                        PlayerAudioArtworkHitTarget(artworkBounds.value, origin.value, true, { clicks++ })
                    }
                }
            }
        }
        waitForCover(Color.Red)
        fun verify(tag: String) {
            val artwork = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val hit = compose.onNodeWithTag("player-audio-artwork-hit-target").fetchSemanticsNode().boundsInRoot
            assertEquals(artwork.left, hit.left, 1f)
            assertEquals(artwork.top, hit.top, 1f)
            assertEquals(artwork.right, hit.right, 1f)
            assertEquals(artwork.bottom, hit.bottom, 1f)
            val host = compose.onNodeWithTag("embedded-audio").fetchSemanticsNode().boundsInRoot
            val before = clicks
            compose.onNodeWithTag("embedded-audio").performTouchInput {
                click(Offset(artwork.center.x - host.left, artwork.top - host.top + 2f))
            }
            compose.runOnIdle { assertEquals("The top of the visible cover must be clickable", before + 1, clicks) }
            compose.onNodeWithTag("embedded-audio").performTouchInput {
                click(Offset(artwork.center.x - host.left, artwork.bottom - host.top + 4f))
            }
            compose.runOnIdle { assertEquals("The area below the cover must not toggle lyrics", before + 1, clicks) }
        }
        verify("player-audio-cover")
        compose.runOnIdle { waveform.value = false; translation.value = 35f }
        verify("player-audio-cover")
        compose.runOnIdle { landscape.value = true; waveform.value = true }
        verify("player-audio-cover")
        compose.runOnIdle { path.value = coverless.path }
        compose.onNodeWithTag("player-audio-vinyl").assertIsDisplayed()
        verify("player-audio-vinyl")
        compose.runOnIdle { landscape.value = false; waveform.value = false; translation.value = -25f }
        verify("player-audio-vinyl")
    }

    private fun showVisual(file: File, landscape: Boolean = false): MutableState<String?> {
        val path = mutableStateOf<String?>(file.path)
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            if (landscape) {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 720
                screenHeightDp = 360
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                SubtitleEditComposeTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PlayerAudioVisual(
                                playing = false,
                                loudness = 0f,
                                loudnessSampleId = 0L,
                                showLoudnessWaveform = true,
                                trackPath = path.value
                            )
                        }
                    }
                }
            }
        }
        return path
    }

    private fun assertCoverAndWaveformAreSeparated() {
        val cover = compose.onNodeWithTag("player-audio-cover").fetchSemanticsNode().boundsInRoot
        val waveform = compose.onNodeWithTag("player-loudness-waveform").fetchSemanticsNode().boundsInRoot
        assertTrue("The audio cover must leave visible space above the waveform", cover.bottom < waveform.top)
    }

    private fun waitForCover(expected: Color) {
        compose.waitUntil(5_000L) {
            if (compose.onAllNodesWithTag("player-audio-cover").fetchSemanticsNodes().isEmpty()) {
                false
            } else {
                val pixels = compose.onNodeWithTag("player-audio-cover").captureToImage().toPixelMap()
                val actual = pixels[pixels.width / 2, pixels.height / 2]
                kotlin.math.abs(actual.red - expected.red) < 0.08f &&
                    kotlin.math.abs(actual.green - expected.green) < 0.08f &&
                    kotlin.math.abs(actual.blue - expected.blue) < 0.08f
            }
        }
    }

    private fun fixtureRoot(): File = File(compose.activity.cacheDir, "player-audio-artwork-${UUID.randomUUID()}")
        .apply { mkdirs(); directory = this }

    private fun createWave(root: File, name: String): File = File(root, name).apply {
        val count = 8_000
        writeBytes(ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(count); putInt(count * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
        }.array())
    }

    private fun attachCover(track: File, color: Int, index: Boolean = true) {
        val image = File(track.parentFile, "cover-$color.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        MediaTagAudioRepository.write(track, MediaConvertMetadata(
            cover = MediaConvertCoverMode.CUSTOM,
            coverPath = image.path
        ))
        PlayerTrackMetadataReader.invalidate(track.path)
        if (index) MediaLibraryRepository.getInstance(compose.activity).indexTrack(track.path)
    }
}
