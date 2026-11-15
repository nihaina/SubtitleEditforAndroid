package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.library.MediaAlbum
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Remounts the real category page while its disk-validation replacement is deliberately delayed. */
@RunWith(AndroidJUnit4::class)
class MediaAlbumTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun returningToCategoriesImmediatelyUsesThePreparedAlbumsDuringValidation() {
        val state = MediaAlbumPageState()
        val visible = mutableStateOf(true)
        val reads = AtomicInteger()
        val validationGate = CompletableDeferred<Unit>()
        val loaded = tracks("Retained album")
        showPage(state, visible = { visible.value }) {
            if (reads.incrementAndGet() > 1) validationGate.await()
            loaded
        }

        awaitAlbum(state, "Retained album")
        lateinit var preparedAlbums: List<MediaAlbum>
        compose.runOnIdle { preparedAlbums = requireNotNull(state.prepared).albums }
        compose.onNodeWithTag("album-item:retained album").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("Another page").assertIsDisplayed()
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(5_000L) { reads.get() >= 2 }

        try {
            // The validation has not returned, so a remounted empty produceState cannot satisfy this.
            compose.onNodeWithTag("album-item:retained album").assertIsDisplayed()
            assertNoLoadingIndicator()
            compose.runOnIdle {
                assertSame(preparedAlbums, requireNotNull(state.prepared).albums)
                assertSame(loaded, requireNotNull(state.catalog).tracks)
            }
        } finally {
            validationGate.complete(Unit)
        }
    }

    @Test fun returningToAnAlreadyLoadedEmptyCatalogDoesNotShowLoadingAgain() {
        val state = MediaAlbumPageState()
        val visible = mutableStateOf(true)
        val reads = AtomicInteger()
        val validationGate = CompletableDeferred<Unit>()
        showPage(state, visible = { visible.value }) {
            if (reads.incrementAndGet() > 1) validationGate.await()
            emptyList()
        }

        compose.waitUntil(5_000L) {
            state.library != null && state.prepared?.history === state.library?.history
        }
        val emptyText = compose.activity.getString(R.string.album_browse_empty)
        compose.onNodeWithText(emptyText).assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("Another page").assertIsDisplayed()
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(5_000L) { reads.get() >= 2 }

        try {
            compose.onNodeWithText(emptyText).assertIsDisplayed()
            assertNoLoadingIndicator()
        } finally {
            validationGate.complete(Unit)
        }
    }

    @Test fun refreshingKeepsPreparedRowsVisibleUntilTheReplacementIsReady() {
        val state = MediaAlbumPageState()
        val refreshVersion = mutableIntStateOf(0)
        val reads = AtomicInteger()
        val refreshGate = CompletableDeferred<Unit>()
        val original = tracks("Original album")
        val replacement = tracks("Updated album")
        showPage(state, refreshVersion = { refreshVersion.intValue }) {
            if (reads.incrementAndGet() == 1) original else {
                refreshGate.await()
                replacement
            }
        }

        awaitAlbum(state, "Original album")
        compose.runOnIdle { refreshVersion.intValue++ }
        compose.waitUntil(5_000L) { reads.get() >= 2 }
        try {
            compose.onNodeWithTag("album-item:original album").assertIsDisplayed()
            compose.onNodeWithTag("album-item:updated album").assertDoesNotExist()
            assertNoLoadingIndicator()
        } finally {
            refreshGate.complete(Unit)
        }

        awaitAlbum(state, "Updated album")
        compose.onNodeWithTag("album-item:updated album").assertIsDisplayed()
        compose.onNodeWithTag("album-item:original album").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requireNotNull(state.catalog).key.refreshVersion) }
    }

    @Test fun changingDirectoriesDoesNotReuseRowsFromAnotherRoot() {
        val state = MediaAlbumPageState()
        val directories = mutableStateOf(listOf(File("/z"), File("/a"), File("/z")))
        val requests = CopyOnWriteArrayList<List<String>>()
        val nextRootGate = CompletableDeferred<Unit>()
        showPage(state, directories = { directories.value }) { roots ->
            requests += roots
            if (roots == listOf("/other/")) {
                nextRootGate.await()
                tracks("Other album", "/other")
            } else tracks("First album", "/a")
        }

        awaitAlbum(state, "First album")
        assertEquals(listOf("/a/", "/z/"), requests.first())
        compose.runOnIdle { directories.value = listOf(File("/other")) }
        compose.waitUntil(5_000L) { requests.lastOrNull() == listOf("/other/") }
        try {
            compose.onNodeWithTag("album-item:first album").assertDoesNotExist()
            compose.onNodeWithTag("album-item:other album").assertDoesNotExist()
        } finally {
            nextRootGate.complete(Unit)
        }

        awaitAlbum(state, "Other album")
        compose.onNodeWithTag("album-item:other album").assertIsDisplayed()
    }

    private fun showPage(
        state: MediaAlbumPageState,
        visible: () -> Boolean = { true },
        refreshVersion: () -> Int = { 0 },
        directories: () -> List<File> = { listOf(File("/album-fixture")) },
        loader: MediaAlbumCatalogLoader,
    ) {
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 600.dp)) {
                    if (visible()) {
                        MediaAlbumScreen(refreshVersion(), directories(), true, state, loader)
                    } else {
                        Text("Another page", Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun awaitAlbum(state: MediaAlbumPageState, name: String) {
        compose.waitUntil(5_000L) {
            val prepared = state.prepared
            prepared?.albums?.singleOrNull()?.name == name &&
                state.library != null && prepared.history === state.library?.history
        }
    }

    private fun assertNoLoadingIndicator() {
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    private fun tracks(album: String, root: String = "/album-fixture") = listOf(
        MediaAlbumTrack(MediaLibraryTrack("$root/song.wav", "Song", album = album)),
    )
}
