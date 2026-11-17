package com.subtitleedit.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.key
import androidx.compose.ui.unit.dp
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
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.SharedFileCache
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
import org.json.JSONArray
import org.json.JSONObject

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
        assertNotNull(SharedFileCache.attributes.get(File(video.path))?.let { metadata -> SharedMediaCache.artwork(metadata, 64) })
        assertNull(SharedFileCache.attributes.get(File(video.path))?.let { metadata -> SharedMediaCache.artwork(metadata, 64, allowVideoFrame = false) })
        assertNotNull(SharedFileCache.attributes.get(File(video.path))?.let { metadata -> SharedMediaCache.artwork(metadata, 64) })
        assertNull(SharedFileCache.attributes.get(File(video.path))?.let { metadata -> SharedMediaCache.artwork(metadata, 64, allowVideoFrame = false) })
    }

    @Test fun knownArtworkSizeMatchesConstraintSizingAfterResizeAndPathReplacement() {
        val root = fixtureRoot()
        val red = createWave(root, "red.wav").also { attachCover(it, AndroidColor.RED, root) }
        val blue = createWave(root, "blue.wav").also { attachCover(it, AndroidColor.BLUE, root) }
        val path = mutableStateOf(red.path)
        val size = mutableStateOf(48.dp)
        compose.setContent {
            SubtitleEditComposeTheme {
                Column {
                    Box(Modifier.testTag("known-size")) {
                        MediaAlbumArtwork(listOf(path.value), Modifier.size(size.value), targetSizePx =
                            with(LocalDensity.current) { size.value.roundToPx() })
                    }
                    Box(Modifier.testTag("constraint-size")) {
                        MediaAlbumArtwork(listOf(path.value), Modifier.size(size.value))
                    }
                }
            }
        }
        fun assertBoth(expected: Color) {
            compose.waitUntil(5_000L) {
                listOf("known-size", "constraint-size").all { tag ->
                    val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
                    val actual = pixels[pixels.width / 2, pixels.height / 2]
                    kotlin.math.abs(actual.red - expected.red) < 0.08f &&
                        kotlin.math.abs(actual.green - expected.green) < 0.08f &&
                        kotlin.math.abs(actual.blue - expected.blue) < 0.08f
                }
            }
            val known = compose.onNodeWithTag("known-size").captureToImage().toPixelMap()
            val reference = compose.onNodeWithTag("constraint-size").captureToImage().toPixelMap()
            org.junit.Assert.assertEquals(reference.width, known.width)
            org.junit.Assert.assertEquals(reference.height, known.height)
            var differences = 0
            for (y in 0 until known.height) for (x in 0 until known.width) {
                if (known[x, y] != reference[x, y]) differences++
            }
            assertTrue("Artwork pixels differ: $differences", differences < known.width * known.height * 0.003)
        }
        assertBoth(Color.Red)
        compose.runOnIdle { size.value = 137.dp }
        assertBoth(Color.Red)
        compose.runOnIdle { path.value = blue.path; size.value = 40.dp }
        assertBoth(Color.Blue)
    }

    /** Compare initial composition plus measure; subcomposition must not just move between them. */
    @Test fun profileKnownAndConstraintSizedArtworkInterleaved() {
        val knownSize = mutableStateOf(false)
        val iteration = mutableStateOf(0)
        var compositionNs = 0L
        var measureNs = 0L
        val samples = JSONArray()
        compose.setContent {
            SubtitleEditComposeTheme {
                key(iteration.value) {
                    Box(Modifier.layout { measurable, constraints ->
                        val start = System.nanoTime()
                        val placeable = measurable.measure(constraints)
                        measureNs += System.nanoTime() - start
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }) {
                        val start = System.nanoTime()
                        Column {
                            repeat(4) {
                                Row {
                                    repeat(3) {
                                        MediaAlbumArtwork(emptyList(), Modifier.size(80.dp), targetSizePx =
                                            if (knownSize.value) with(LocalDensity.current) { 80.dp.roundToPx() } else null)
                                    }
                                }
                            }
                        }
                        compositionNs += System.nanoTime() - start
                    }
                }
            }
        }
        compose.waitForIdle()
        try {
            repeat(40) { pair ->
                for (known in if (pair % 2 == 0) listOf(false, true) else listOf(true, false)) {
                    compose.runOnIdle {
                        compositionNs = 0L; measureNs = 0L
                        knownSize.value = known
                        iteration.value++
                    }
                    compose.waitForIdle()
                    compose.runOnIdle {
                        samples.put(JSONObject().put("pair", pair).put("known_size", known)
                            .put("composition_ms", compositionNs / 1e6).put("measure_ms", measureNs / 1e6))
                    }
                }
            }
        } finally {
            File(compose.activity.cacheDir, "album-artwork-sizing-profile.json").writeText(samples.toString(2))
        }
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
