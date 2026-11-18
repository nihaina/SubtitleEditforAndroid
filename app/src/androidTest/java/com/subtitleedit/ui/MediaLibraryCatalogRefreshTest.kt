package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.MediaAlbumMetadata
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryCatalogRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun historyRefreshReusesCatalogButIndexAndRootChangesReloadIt() {
        val app = compose.activity.applicationContext
        val databaseName = "catalog-refresh-${System.nanoTime()}.db"
        val fixture = File(app.cacheDir, databaseName).apply { check(mkdir()) }
        val root = File(fixture, "primary").apply { check(mkdir()) }
        val otherRoot = File(fixture, "other").apply { check(mkdir()) }
        val first = File(root, "first.wav").apply { writeText("fixture") }
        val second = File(root, "second.wav").apply { writeText("fixture") }
        val other = File(otherRoot, "other.wav").apply { writeText("fixture") }
        val roots = mutableStateOf(listOf(root))
        val active = mutableStateOf(true)
        val refreshVersion = mutableIntStateOf(0)
        val page = MediaLibraryPageState()
        val catalogReads = AtomicInteger()

        try {
            MediaLibraryRepository.open(app, databaseName,
                albumMetadataReader = { MediaAlbumMetadata() },
                metadataReader = { entry, duration ->
                    MediaLibraryTrack(entry.path, File(entry.path).name, durationMs = duration)
                }
            ).use { repository ->
                repository.indexTrack(first.path, durationMs = 20_000L)
                repository.indexTrack(other.path, durationMs = 15_000L)
                repository.recordPlaybackProgress(first.path, "fixture-playback", 20_000L, 1_000L)
                compose.setContent {
                    SubtitleEditComposeTheme {
                        MediaLibraryScreen(
                            refreshVersion = refreshVersion.intValue,
                            selectionVersion = 0,
                            onToolbarChanged = { _, _, _ -> },
                            mediaLibraryDirectories = roots.value,
                            isActive = active.value,
                            pageState = page,
                            libraryRepository = repository,
                            readIndexedTracks = {
                                catalogReads.incrementAndGet()
                                it.getIndexedTracks()
                            }
                        )
                    }
                }
                compose.waitUntil(5_000L) {
                    val catalog = page.catalog ?: return@waitUntil false
                    page.browseIndex?.tracks === catalog.tracks &&
                        page.content.history.singleOrNull()?.positionMs == 1_000L
                }
                val initialCatalog = compose.runOnIdle { requireNotNull(page.catalog) }
                val initialBrowseIndex = compose.runOnIdle { requireNotNull(page.browseIndex) }
                assertEquals(listOf(first.path), initialCatalog.tracks.map { it.path })
                assertEquals(1, catalogReads.get())

                repository.recordPlaybackProgress(first.path, "fixture-playback", 20_000L, 5_000L)
                compose.waitUntil(5_000L) { page.content.history.singleOrNull()?.positionMs == 5_000L }
                repository.recordListeningInterval(first.path, "fixture-playback", System.currentTimeMillis(), 5_000L)
                compose.waitUntil(5_000L) { page.loadedQuery?.revision == repository.revision.value }
                compose.runOnIdle { refreshVersion.intValue++ }
                compose.waitUntil(5_000L) { page.loadedQuery?.refreshVersion == 1 }
                compose.runOnIdle {
                    assertEquals(1, catalogReads.get())
                    assertSame(initialCatalog, page.catalog)
                    assertSame(initialBrowseIndex, page.browseIndex)
                }

                compose.runOnIdle { active.value = false }
                compose.waitForIdle()
                repository.indexTrack(second.path)
                compose.waitForIdle()
                assertEquals(1, catalogReads.get())
                compose.runOnIdle { active.value = true }
                compose.waitUntil(5_000L) { page.catalog?.tracks?.size == 2 }
                assertEquals(2, catalogReads.get())

                compose.runOnIdle { roots.value = listOf(otherRoot) }
                compose.waitUntil(5_000L) { page.catalog?.tracks?.singleOrNull()?.path == other.path }
                assertEquals(3, catalogReads.get())

                compose.runOnIdle { roots.value = emptyList() }
                compose.waitUntil(5_000L) { page.catalog?.tracks?.isEmpty() == true }
                assertEquals(3, catalogReads.get())
            }
        } finally {
            app.deleteDatabase(databaseName)
            fixture.deleteRecursively()
        }
    }
}
