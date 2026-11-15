package com.subtitleedit.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.library.ListeningDayStat
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.SharedFileCache
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.library.TopListeningItem
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real embedded pictures exercise the summary's period and no-fallback artwork policy. */
@RunWith(AndroidJUnit4::class)
class ListeningStatsArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixtureDirectory: File? = null

    @After fun removeFixtures() {
        fixtureDirectory?.listFiles()?.forEach { PlayerTrackMetadataReader.invalidate(it.path) }
        fixtureDirectory?.deleteRecursively()
    }

    @Test fun dayWeekMonthAndYearShowTheirOwnLongestPlayedWork() {
        val root = fixtureRoot()
        val expected = linkedMapOf(
            StatsGranularity.DAY to (Color.Red to AndroidColor.RED),
            StatsGranularity.WEEK to (Color.Blue to AndroidColor.BLUE),
            StatsGranularity.MONTH to (Color.Green to AndroidColor.GREEN),
            StatsGranularity.YEAR to (Color.Yellow to AndroidColor.YELLOW),
        ).mapValues { (mode, colors) ->
            createWave(root, "$mode.wav").also { attachCover(it, colors.second, root) } to colors.first
        }
        val state = ListeningStatsPageState()
        showStatistics(state) { today, _, mode, _ ->
            val winner = requireNotNull(expected[mode]).first
            val runnerUp = expected.values.first { it.first != winner }.first
            content(today, listOf(item(winner, 120_000L), item(runnerUp, 60_000L)))
        }

        expected.forEach { (mode, entry) ->
            compose.runOnIdle { state.granularity = mode }
            compose.waitUntil(5_000L) { state.content?.topItems?.firstOrNull()?.mediaPath == entry.first.path }
            waitForArtworkColor(entry.second)
        }
    }

    @Test fun missingWinnerCoverDoesNotUseTheRunnerUpOrACachedVideoFrame() {
        val root = fixtureRoot()
        val video = createVideo(root)
        val blank = createWave(root, "no cover.wav")
        val runnerUp = createWave(root, "runner up.wav").also { attachCover(it, AndroidColor.RED, root) }
        // Prewarm the exact thumbnail size used by the summary with a valid video frame.
        assertNotNull(SharedFileCache.attributes.get(File(video.path))?.let { metadata -> SharedMediaCache.artwork(metadata, 480) })
        val state = ListeningStatsPageState()
        showStatistics(state) { today, _, mode, _ ->
            val winner = if (mode == StatsGranularity.DAY) video else blank
            content(today, listOf(item(winner, 120_000L), item(runnerUp, 60_000L)))
        }

        compose.waitUntil(5_000L) { state.content?.topItems?.firstOrNull()?.mediaPath == video.path }
        assertNoArtworkDuring()
        compose.runOnIdle { state.granularity = StatsGranularity.WEEK }
        compose.waitUntil(5_000L) { state.content?.topItems?.firstOrNull()?.mediaPath == blank.path }
        assertNoArtworkDuring()
    }

    @Test fun switchingPeriodsHidesThePreviousCoverWhileTheNewQueryIsPending() {
        val root = fixtureRoot()
        val covered = createWave(root, "day winner.wav").also { attachCover(it, AndroidColor.RED, root) }
        val blank = createWave(root, "week winner.wav")
        val state = ListeningStatsPageState()
        val dayRange = StatsCalculator.currentRange(state.selectedDate, StatsGranularity.DAY)
        val nextPeriod = StatsGranularity.entries.firstOrNull {
            StatsCalculator.currentRange(state.selectedDate, it) != dayRange
        }
        // On January 1 falling on Monday, all four current ranges describe the same day.
        assumeNotNull(nextPeriod)
        val weekReadStarted = AtomicBoolean()
        val weekGate = CompletableDeferred<Unit>()
        showStatistics(state) { today, _, mode, _ ->
            if (mode == nextPeriod) {
                weekReadStarted.set(true)
                weekGate.await()
                content(today, listOf(item(blank, 240_000L), item(covered, 120_000L)))
            } else {
                content(today, listOf(item(covered, 120_000L)))
            }
        }

        waitForArtworkColor(Color.Red)
        compose.runOnIdle { state.granularity = requireNotNull(nextPeriod) }
        compose.waitUntil(5_000L) { weekReadStarted.get() }
        try {
            assertNoArtworkDuring()
            assertTrue("The next period must still be waiting for its query", !weekGate.isCompleted)
        } finally {
            weekGate.complete(Unit)
        }
        compose.waitUntil(5_000L) { state.content?.topItems?.firstOrNull()?.mediaPath == blank.path }
        assertNoArtworkDuring()
    }

    private fun showStatistics(state: ListeningStatsPageState, loader: ListeningStatsLoader) {
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 600.dp)) {
                    ListeningStatsScreen(0, state, loader)
                }
            }
        }
    }

    private fun content(today: String, items: List<TopListeningItem>) = ListeningStatsContent(
        allStats = listOf(ListeningDayStat(today, items.sumOf { it.totalDurationMs }, items.size, items.size)),
        availableYears = listOf(today.take(4).toInt()),
        topItems = items,
    )

    private fun item(file: File, durationMs: Long) =
        TopListeningItem(file.path, file.nameWithoutExtension, "", "", durationMs, 1)

    private fun waitForArtworkColor(expected: Color) {
        compose.waitUntil(5_000L) {
            if (compose.onAllNodesWithTag("stats-summary-artwork", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()) return@waitUntil false
            val pixels = compose.onNodeWithTag("stats-summary-artwork", useUnmergedTree = true)
                .captureToImage().toPixelMap()
            // The middle lies in the gap between the four metric cards, over the cover itself.
            val actual = pixels[pixels.width / 2, pixels.height / 2]
            val channels = listOf(actual.red, actual.green, actual.blue)
            val selected = listOf(expected.red, expected.green, expected.blue)
            selected.indices.all { high ->
                selected[high] < 0.5f || selected.indices.all { low ->
                    selected[low] >= 0.5f || channels[high] > channels[low] + 0.06f
                }
            }
        }
    }

    private fun assertNoArtworkDuring() {
        // Check a real interval so an asynchronously decoded fallback cannot pass before it arrives.
        val deadline = SystemClock.uptimeMillis() + 500L
        compose.waitUntil(2_000L) {
            assertTrue(
                "A missing or pending winner must leave the summary background empty",
                compose.onAllNodesWithTag("stats-summary-artwork", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty(),
            )
            SystemClock.uptimeMillis() >= deadline
        }
    }

    private fun fixtureRoot(): File = File(compose.activity.cacheDir, "stats-artwork-${UUID.randomUUID()}")
        .apply { check(mkdirs()); fixtureDirectory = this }

    private fun createVideo(root: File): File = File(root, "no cover.mp4").also { file ->
        val generated = FFmpegKit.executeWithArguments(arrayOf(
            "-y", "-f", "lavfi", "-i", "color=c=blue:s=32x32:r=1:d=1",
            "-c:v", "mpeg4", "-an", file.path,
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
            coverPath = picture.path,
        ))
        PlayerTrackMetadataReader.invalidate(audio.path)
    }
}
