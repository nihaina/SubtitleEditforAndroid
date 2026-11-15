package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.buildMediaAlbums
import com.subtitleedit.ui.components.AppLazyGridScrollbar
import com.subtitleedit.ui.components.AppLazyListScrollbar
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pointer drags exercise the real lazy states, including variable headers and partial grid rows. */
@RunWith(AndroidJUnit4::class)
class MediaCategoryScrollbarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun listRailReachesBothEndsAndDisappearsWhenItsContentFits() {
        val count = mutableIntStateOf(100)
        lateinit var state: LazyListState
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(300.dp, 300.dp)) {
                    Box {
                        state = rememberLazyListState()
                        LazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp),
                        ) {
                            item { Text("A taller detail header", Modifier.height(126.dp)) }
                            items(count.intValue) { index ->
                                Text("Row $index", Modifier.height(if (index % 3 == 0) 64.dp else 40.dp))
                            }
                        }
                        AppLazyListScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }

        compose.onNodeWithTag(RAIL).assertIsDisplayed()
        dragThumbToEnd(bottom = true)
        compose.waitUntil(5_000L) { !state.canScrollForward && state.canScrollBackward }
        compose.runOnIdle { assertEquals(100, state.layoutInfo.visibleItemsInfo.last().index) }
        dragThumbToEnd(bottom = false)
        compose.waitUntil(5_000L) { !state.canScrollBackward }
        compose.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
        }

        // The accessibility action uses the same range as the draggable rail.
        compose.onNodeWithTag(RAIL).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitUntil(5_000L) { state.firstVisibleItemIndex > 10 && state.canScrollForward }
        compose.runOnIdle { count.intValue = 1 }
        compose.waitUntil(5_000L) { !state.canScrollBackward && !state.canScrollForward }
        compose.onNodeWithTag(RAIL).assertDoesNotExist()
    }

    @Test fun gridRailHandlesPartialRowsAndColumnChanges() {
        val columns = mutableIntStateOf(3)
        val count = mutableIntStateOf(101)
        lateinit var state: LazyGridState
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(300.dp, 280.dp)) {
                    Box {
                        state = rememberLazyGridState()
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns.intValue),
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                        ) {
                            items(count.intValue) { index ->
                                Text("Cell $index", Modifier.height(64.dp).testTag("cell:$index"))
                            }
                        }
                        AppLazyGridScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }

        dragThumbToEnd(bottom = true)
        compose.waitUntil(5_000L) { !state.canScrollForward && state.canScrollBackward }
        compose.onNodeWithTag("cell:100").assertIsDisplayed()
        compose.runOnIdle { columns.intValue = 2 }
        compose.waitForIdle()
        dragThumbToEnd(bottom = false)
        compose.waitUntil(5_000L) { !state.canScrollBackward }
        compose.onNodeWithTag("cell:0").assertIsDisplayed()
        dragThumbToEnd(bottom = true)
        compose.waitUntil(5_000L) { !state.canScrollForward }
        compose.onNodeWithTag("cell:100").assertIsDisplayed()

        compose.runOnIdle { count.intValue = 2 }
        compose.waitUntil(5_000L) { !state.canScrollForward && !state.canScrollBackward }
        compose.onNodeWithTag(RAIL).assertDoesNotExist()
    }

    @Test fun allFiveCategoryPagesExposeADraggableRailAndShortResultsHideIt() {
        val albums = mutableStateOf(buildMediaAlbums(categoryTracks(80)))
        val options = mutableStateOf(MediaAlbumViewOptions(viewMode = AlbumViewMode.LIST_NO_ART))
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 500.dp)) {
                    MediaAlbumPageContent(
                        albums = albums.value,
                        options = options.value,
                        onOptionsChanged = { options.value = it },
                        onPlay = { _, _ -> },
                    )
                }
            }
        }

        val prefixes = linkedMapOf(
            MediaCategory.ALBUMS to "album",
            MediaCategory.SINGERS to "singer",
            MediaCategory.ARTISTS to "artist",
            MediaCategory.COMPOSERS to "composer",
            MediaCategory.GENRES to "genre",
        )
        prefixes.forEach { (category, prefix) ->
            val categoryTag = "media-category:${category.name}"
            compose.onNodeWithTag("media-category-tabs").performScrollToNode(hasTestTag(categoryTag))
            compose.onNodeWithTag(categoryTag).performClick()
            compose.onNodeWithTag(RAIL).assertIsDisplayed()
            dragThumbToEnd(bottom = true)
            compose.onNodeWithTag("$prefix-item:$prefix 079").assertIsDisplayed()
            dragThumbToEnd(bottom = false)
            compose.onNodeWithTag("$prefix-item:$prefix 000").assertIsDisplayed()
        }

        compose.runOnIdle { albums.value = buildMediaAlbums(categoryTracks(1)) }
        compose.waitForIdle()
        compose.onNodeWithTag("genre-item:genre 000").assertIsDisplayed()
        compose.onNodeWithTag(RAIL).assertDoesNotExist()
        compose.runOnIdle { albums.value = emptyList() }
        compose.waitForIdle()
        compose.onNodeWithTag(RAIL).assertDoesNotExist()
    }

    @Test fun albumGridUsesTheRailWithoutChangingItsPlaybackCallback() {
        val albums = buildMediaAlbums(categoryTracks(80))
        var opened: String? = null
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 500.dp)) {
                    MediaAlbumBrowseContent(
                        albums = albums,
                        options = MediaAlbumViewOptions(viewMode = AlbumViewMode.GRID),
                        query = "",
                        onQueryChanged = {},
                        onSort = {},
                        onSettings = {},
                        onOpenAlbum = { opened = it.name },
                    )
                }
            }
        }

        compose.onNodeWithTag(RAIL).assertIsDisplayed()
        dragThumbToEnd(bottom = true)
        compose.onNodeWithTag("album-item:album 079").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Album 079", opened) }
    }

    @Test fun aSingleOversizedListOrGridRowCanReachItsBottom() {
        val grid = mutableStateOf(false)
        lateinit var listState: LazyListState
        lateinit var gridState: LazyGridState
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(300.dp, 200.dp)) {
                    Box {
                        if (grid.value) {
                            gridState = rememberLazyGridState()
                            LazyVerticalGrid(columns = GridCells.Fixed(1), state = gridState,
                                modifier = Modifier.fillMaxSize()) {
                                item { Text("Tall grid cell", Modifier.height(600.dp)) }
                            }
                            AppLazyGridScrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                        } else {
                            listState = rememberLazyListState()
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                item { Text("Tall list row", Modifier.height(600.dp)) }
                            }
                            AppLazyListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                        }
                    }
                }
            }
        }
        dragThumbToEnd(bottom = true)
        compose.waitUntil(5_000L) { listState.canScrollBackward && !listState.canScrollForward }
        dragThumbToEnd(bottom = false)
        compose.waitUntil(5_000L) { !listState.canScrollBackward }
        compose.runOnIdle { grid.value = true }
        dragThumbToEnd(bottom = true)
        compose.waitUntil(5_000L) { gridState.canScrollBackward && !gridState.canScrollForward }
        dragThumbToEnd(bottom = false)
        compose.waitUntil(5_000L) { !gridState.canScrollBackward }
    }

    private fun dragThumbToEnd(bottom: Boolean) {
        val rail = compose.onNodeWithTag(RAIL).fetchSemanticsNode().boundsInRoot
        val thumb = compose.onNodeWithTag(THUMB, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val start = Offset(rail.width / 2f, thumb.center.y - rail.top)
        val endY = if (bottom) rail.height - 1f else 1f
        compose.onNodeWithTag(RAIL).performTouchInput {
            swipe(start, Offset(start.x, endY), durationMillis = 500L)
        }
        compose.waitForIdle()
    }

    private fun categoryTracks(count: Int) = List(count) { index ->
        val number = index.toString().padStart(3, '0')
        MediaAlbumTrack(
            track = MediaLibraryTrack("/missing-scrollbar-fixture/$number.wav", "Song $number",
                artist = "Singer $number", album = "Album $number"),
            albumArtist = "Artist $number",
            composer = "Composer $number",
            genre = "Genre $number",
            trackNumber = index + 1,
        )
    }

    private companion object {
        const val RAIL = "app-lazy-scrollbar"
        const val THUMB = "app-lazy-scrollbar-thumb"
    }
}
