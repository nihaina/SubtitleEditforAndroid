package com.subtitleedit.ui

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.library.MediaAlbumMetadata
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SharedFileCache
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryLargeIndexTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun playbackWritesReuseLargeCatalogAndLoadRankingsOnlyWhenSelected() {
        val app = compose.activity.applicationContext
        val database = "large-index-${System.nanoTime()}.db"
        val root = File(app.cacheDir, database).apply { check(mkdir()) }
        val reads = AtomicInteger()
        val metadataReads = AtomicInteger()
        val libraryPage = MediaLibraryPageState()
        val statsPage = ListeningStatsPageState()
        val showStats = mutableStateOf(false)
        val roots = listOf(root)
        try {
            val wav = silentWav()
            // Stable names make the first few tracks in the NOT PLAYED sort predictable.
            val files = List(4) { dir ->
                File(root, "disc-$dir").apply { check(mkdir()) }.let { folder ->
                    List(1_024) { index ->
                        File(folder, "track-$dir-${index.toString().padStart(4, '0')}.wav").apply { writeBytes(wav) }
                    }
                }
            }.flatten()
            MediaLibraryRepository.open(app, database,
                albumMetadataReader = { MediaAlbumMetadata() },
                metadataReader = { entry, _ ->
                    metadataReads.incrementAndGet()
                    MediaLibraryTrack(entry.path, entry.file.nameWithoutExtension, durationMs = 100L)
                }
            ).use { repository ->
                fun scan(label: String) {
                    val start = SystemClock.elapsedRealtime()
                    assertEquals(files.size, repository.scanDirectoriesToLibrary(roots))
                    Log.i(TAG, "$label: ${files.size} candidates in ${SystemClock.elapsedRealtime() - start} ms; cumulative metadata reads=${metadataReads.get()}")
                }
                scan("initial scan")
                assertEquals(files.size, metadataReads.get())
                val indexedRevision = repository.catalogRevision.value
                scan("unchanged scan")
                assertEquals(files.size, metadataReads.get())
                assertEquals(indexedRevision, repository.catalogRevision.value)

                val first = files[0]
                val second = files[1]
                val third = files[2]
                val removed = files.last()
                val added = File(removed.parentFile, "zz-new.wav")
                fun progress(file: File, playbackId: String, position: Long) {
                    repository.recordPlaybackProgress(file.path, playbackId, 100L, position)
                }
                progress(first, "first", 1L)
                compose.setContent {
                    SubtitleEditComposeTheme {
                        if (showStats.value) {
                            ListeningStatsScreen(0, statsPage, libraryRepository = repository)
                        } else {
                            MediaLibraryScreen(
                                refreshVersion = 0,
                                selectionVersion = 0,
                                onToolbarChanged = { _, _, _ -> },
                                mediaLibraryDirectories = roots,
                                pageState = libraryPage,
                                libraryRepository = repository,
                                readIndexedTracks = {
                                    reads.incrementAndGet()
                                    it.getIndexedTracks()
                                }
                            )
                        }
                    }
                }
                compose.waitUntil(TIMEOUT) {
                    libraryPage.catalog?.tracks?.size == files.size &&
                        libraryPage.browseIndex?.tracks === libraryPage.catalog?.tracks &&
                        libraryPage.content.history.singleOrNull()?.positionMs == 1L
                }
                val catalog = compose.runOnIdle { requireNotNull(libraryPage.catalog) }
                val browse = compose.runOnIdle { requireNotNull(libraryPage.browseIndex) }
                assertEquals(1, reads.get())
                // Observe six separate refreshes, not just the end of a coalesced write burst.
                repeat(6) { batch ->
                    repeat(4) { progress(first, "first", 2L + batch * 4L + it) }
                    repository.recordListeningInterval(first.path, "first", System.currentTimeMillis(), 500L)
                    val revision = repository.revision.value
                    compose.waitUntil(TIMEOUT) {
                        libraryPage.loadedQuery?.revision == revision &&
                            libraryPage.content.history.singleOrNull()?.positionMs == 5L + batch * 4L
                    }
                    compose.runOnIdle {
                        assertEquals(1, reads.get())
                        assertSame(catalog, libraryPage.catalog)
                        assertSame(browse, libraryPage.browseIndex)
                    }
                }
                Log.i(TAG, "24 progress + 6 listening writes: ${reads.get()} total catalog reads including first load")

                check(removed.delete())
                added.writeBytes(wav)
                SharedFileCache.directories.invalidate(added.parentFile!!.path)
                scan("one addition and one removal")
                assertEquals(files.size + 1, metadataReads.get())
                compose.waitUntil(TIMEOUT) {
                    libraryPage.catalog?.tracks?.any { it.path == added.path } == true
                }
                assertEquals(2, reads.get())
                assertFalse(compose.runOnIdle { requireNotNull(libraryPage.catalog).tracks.any { it.path == removed.path } })

                compose.runOnIdle { showStats.value = true }
                compose.waitUntil(TIMEOUT) { statsPage.content?.recentHistory?.singleOrNull()?.path == first.path }
                compose.runOnIdle {
                    assertNull(statsPage.mostPlayed)
                    assertNull(statsPage.neverPlayed)
                }
                selectInsight(R.string.stats_section_most_played)
                compose.waitUntil(TIMEOUT) { statsPage.mostPlayed?.entries?.singleOrNull()?.path == first.path }
                compose.runOnIdle { assertNull(statsPage.neverPlayed) }

                selectInsight(R.string.stats_section_never_played)
                compose.waitUntil(TIMEOUT) { statsPage.neverPlayed?.entries?.size == 100 }
                val firstRanking = compose.runOnIdle { requireNotNull(statsPage.neverPlayed) }
                assertEquals(second.path, firstRanking.entries.first().path)
                val secondRow = "stats-insight-row:${second.path}"
                compose.onNodeWithTag("listening-stats-page").performScrollToNode(hasTestTag(secondRow))
                compose.onNodeWithTag(secondRow).assertIsDisplayed()
                selectInsight(R.string.stats_section_recent)
                selectInsight(R.string.stats_section_never_played)
                compose.runOnIdle { assertSame(firstRanking, statsPage.neverPlayed) }

                selectInsight(R.string.stats_section_recent)
                progress(second, "second", 2L)
                val revision = repository.revision.value
                compose.waitUntil(TIMEOUT) { statsPage.loadedQuery?.revision == revision }
                compose.runOnIdle { assertSame(firstRanking, statsPage.neverPlayed) }
                selectInsight(R.string.stats_section_never_played)
                compose.waitUntil(TIMEOUT) { statsPage.neverPlayed?.version?.revision == revision }
                assertEquals(third.path, compose.runOnIdle { requireNotNull(statsPage.neverPlayed).entries.first().path })
            }
        } finally {
            app.deleteDatabase(database)
            root.deleteRecursively()
        }
    }

    private fun selectInsight(labelRes: Int) {
        val label = compose.activity.getString(labelRes)
        compose.onNodeWithTag("listening-stats-page").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }

    private fun silentWav(): ByteArray {
        val samples = 800 // 100 ms, 8 kHz PCM mono
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2).array()
    }

    private companion object {
        const val TAG = "LargeMediaIndexTest"
        const val TIMEOUT = 15_000L
    }
}
