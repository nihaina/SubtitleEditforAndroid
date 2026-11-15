package com.subtitleedit.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.AlbumTrackSortField
import com.subtitleedit.library.AlbumTrackSortOptions
import com.subtitleedit.library.MediaAlbum
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.buildMediaAlbums
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAlbumPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixtureDirectory: File? = null

    @After fun removeFixture() {
        fixtureDirectory?.deleteRecursively()
    }

    @Test fun searchFiltersAlbumsAndOtherCategoriesRemainEmpty() {
        showAlbums()
        compose.onNodeWithTag("album-search").performTextInput("bEtA")
        compose.onNodeWithTag("album-item:beta").assertIsDisplayed()
        compose.onNodeWithTag("album-item:alpha").assertDoesNotExist()

        MediaCategory.entries.filter { it != MediaCategory.ALBUMS && it != MediaCategory.SINGERS }.forEach { category ->
            selectCategory(category)
            compose.onNodeWithTag("media-category-empty").assertIsDisplayed()
            compose.onNodeWithTag("album-search").assertDoesNotExist()
        }
        selectCategory(MediaCategory.ALBUMS)
        compose.onNodeWithTag("album-item:beta").assertIsDisplayed()
    }

    @Test fun repeatedSortCriterionReversesTheDisplayedOrder() {
        val harness = showAlbums(MediaAlbumViewOptions(viewMode = AlbumViewMode.LIST_NO_ART))
        assertBefore("alpha", "beta")
        chooseSort(AlbumSortField.NAME)
        compose.runOnIdle { assertFalse(harness.options.value.ascending) }
        assertBefore("beta", "alpha")

        chooseSort(AlbumSortField.TRACK_COUNT)
        compose.runOnIdle {
            assertEquals(AlbumSortField.TRACK_COUNT, harness.options.value.sortField)
            assertFalse(harness.options.value.ascending)
        }
        assertBefore("alpha", "beta")
        chooseSort(AlbumSortField.TRACK_COUNT)
        compose.runOnIdle { assertTrue(harness.options.value.ascending) }
        assertBefore("beta", "alpha")
    }

    @Test fun sortChangesRemainDraftUntilConfirmedAndCancelRestoresTheAppliedOrder() {
        val initial = MediaAlbumViewOptions(viewMode = AlbumViewMode.LIST_NO_ART)
        val harness = showAlbums(initial)
        compose.onNodeWithTag("album-sort").performClick()
        compose.onNodeWithTag("album-sort:NAME").performClick()
        compose.onNodeWithTag("album-sort:DURATION").performClick()
        compose.onNodeWithTag("album-sort:DURATION").performClick()
        compose.onNodeWithTag("album-sort-confirm").assertIsDisplayed()
        compose.runOnIdle { assertEquals(initial, harness.options.value) }
        compose.onNodeWithTag("album-sort-cancel").performClick()
        compose.onNodeWithTag("album-sort-confirm").assertDoesNotExist()
        assertBefore("alpha", "beta")

        compose.onNodeWithTag("album-sort").performClick()
        compose.onNodeWithTag("album-sort:NAME").assertIsSelected()
        compose.onNodeWithTag("album-sort:YEAR").performClick()
        compose.onNodeWithTag("album-sort:YEAR").performClick()
        compose.runOnIdle { assertEquals(initial, harness.options.value) }
        compose.onNodeWithTag("album-sort-confirm").performClick()
        compose.runOnIdle {
            assertEquals(AlbumSortField.YEAR, harness.options.value.sortField)
            assertFalse(harness.options.value.ascending)
        }
        compose.onNodeWithTag("album-sort-confirm").assertDoesNotExist()
        assertBefore("beta", "alpha")
    }

    @Test fun backDismissesSortDraftWithoutApplyingIt() {
        val initial = MediaAlbumViewOptions(viewMode = AlbumViewMode.LIST_NO_ART)
        val harness = showAlbums(initial)
        compose.onNodeWithTag("album-sort").performClick()
        compose.onNodeWithTag("album-sort:DURATION").performClick()
        pressBack()
        compose.onNodeWithTag("album-sort-confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(initial, harness.options.value) }
        assertBefore("alpha", "beta")
    }

    @Test fun listModeDisablesGridSizeAndKeepsTheRememberedSizeForGridMode() {
        val harness = showAlbums(MediaAlbumViewOptions(gridSize = AlbumGridSize.SMALL))
        compose.onNodeWithTag("album-settings").performClick()
        settingsRow("album-settings-view-mode").performClick()
        compose.onNodeWithText(compose.activity.getString(AlbumViewMode.LIST_ART.labelRes)).performClick()
        settingsRow("album-settings-grid-size").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText(compose.activity.getString(AlbumGridSize.LARGE.labelRes)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(AlbumGridSize.SMALL, harness.options.value.gridSize) }

        settingsRow("album-settings-view-mode").performClick()
        compose.onNodeWithText(compose.activity.getString(AlbumViewMode.GRID.labelRes)).performClick()
        settingsRow("album-settings-grid-size").assertIsEnabled().performClick()
        compose.onNodeWithText(compose.activity.getString(AlbumGridSize.LARGE.labelRes)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(AlbumGridSize.SMALL, harness.options.value.gridSize) }
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
    }

    @Test fun gridSizesShowFourThreeAndTwoColumnsAtPhoneWidth() {
        val size = mutableStateOf(AlbumGridSize.SMALL)
        val albums = (1..8).map { index ->
            val name = "Album ${index.toString().padStart(2, '0')}"
            MediaAlbum(name, listOf(MediaAlbumTrack(MediaLibraryTrack("/missing/$index.m4a", name, album = name))))
        }
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 500.dp)) {
                    MediaAlbumBrowseContent(
                        albums = albums,
                        options = MediaAlbumViewOptions(gridSize = size.value, quickSearch = false),
                        query = "",
                        onQueryChanged = {},
                        onSort = {},
                        onSettings = {},
                        onOpenAlbum = {}
                    )
                }
            }
        }
        assertGridColumns(4)
        compose.runOnIdle { size.value = AlbumGridSize.MEDIUM }
        assertGridColumns(3)
        compose.runOnIdle { size.value = AlbumGridSize.LARGE }
        assertGridColumns(2)
    }

    @Test fun settingsSwitchBetweenGridAndBothListPresentations() {
        val harness = showAlbums()
        compose.onNodeWithTag("album-grid").assertIsDisplayed()
        chooseView(AlbumViewMode.LIST_NO_ART)
        compose.onNodeWithTag("album-list").assertIsDisplayed()
        compose.onAllNodesWithTag("album-artwork", useUnmergedTree = true).assertCountEquals(0)
        chooseView(AlbumViewMode.LIST_ART)
        compose.onNodeWithTag("album-list").assertIsDisplayed()
        compose.onAllNodesWithTag("album-artwork", useUnmergedTree = true).assertCountEquals(2)
        chooseView(AlbumViewMode.GRID)
        compose.onNodeWithTag("album-grid").assertIsDisplayed()
        compose.runOnIdle { assertEquals(AlbumViewMode.GRID, harness.options.value.viewMode) }
    }

    @Test fun hidingQuickSearchRestoresAllAlbumsAndMoreInfoAddsMetadata() {
        val harness = showAlbums()
        compose.onNodeWithTag("album-search").performTextInput("Beta")
        compose.onNodeWithTag("album-settings").performClick()
        settingsRow("album-settings-more-info").performClick()
        settingsRow("album-settings-quick-search").performClick()
        compose.onNodeWithTag("album-settings-back").performClick()
        compose.onNodeWithTag("album-search").assertDoesNotExist()
        compose.onNodeWithTag("album-item:alpha").assertIsDisplayed()
        compose.onNodeWithTag("album-item:beta").assertIsDisplayed()
        compose.onNodeWithText("Singer A").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(harness.options.value.quickSearch)
            assertTrue(harness.options.value.showMoreInfo)
        }
    }

    @Test fun albumDetailsKeepTrackAndWholeAlbumPlaybackDistinct() {
        val harness = showAlbums()
        compose.onNodeWithTag("album-item:alpha").performClick()
        compose.onNodeWithTag("album-detail").assertIsDisplayed()
        compose.onNodeWithTag("album-play-all").performClick()
        val firstPath = harness.albums.first { it.key == "alpha" }.tracks.first().track.path
        compose.onNodeWithTag("album-tracks").performScrollToNode(hasTestTag("album-track:$firstPath"))
        compose.onNodeWithTag("album-track:$firstPath").performClick()
        compose.runOnIdle {
            assertEquals(listOf("alpha" to null, "alpha" to firstPath), harness.played)
        }
        compose.onNodeWithTag("album-detail-back").performClick()
        compose.onNodeWithTag("album-settings").performClick()
        settingsRow("album-settings-group-tracks").performClick()
        compose.onNodeWithTag("album-settings-back").performClick()
        compose.onNodeWithTag("album-item:alpha").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.media_category_singers)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.media_category_artists)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.media_category_composers)).assertDoesNotExist()
        compose.onNodeWithTag("album-tracks").performScrollToNode(hasTestTag("album-track:$firstPath"))
        compose.onNodeWithTag("album-track:$firstPath").assertIsDisplayed()
    }

    @Test fun landscapeKeepsLastSettingsAndSortChoicesReachable() {
        val previous = compose.activity.requestedOrientation
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5_000L) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        try {
            val harness = showAlbums()
            compose.onNodeWithTag("album-settings").assertIsDisplayed().performClick()
            settingsRow("album-settings-quick-search").assertIsDisplayed().performClick()
            compose.runOnIdle { assertFalse(harness.options.value.quickSearch) }
            compose.onNodeWithTag("album-settings-back").assertIsDisplayed().performClick()
            compose.onNodeWithTag("album-sort").assertIsDisplayed().performClick()
            compose.onNodeWithTag("album-sort-list").performScrollToNode(hasTestTag("album-sort:ARTIST"))
            compose.onNodeWithTag("album-sort:ARTIST").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(AlbumSortField.NAME, harness.options.value.sortField) }
            compose.onNodeWithTag("album-sort-confirm").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(AlbumSortField.ARTIST, harness.options.value.sortField) }
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = previous }
        }
    }

    @Test fun preferencesRoundTripAllAlbumPresentationOptions() {
        val preferences = MediaAlbumPreferences(compose.activity)
        val previous = preferences.load()
        val changed = MediaAlbumViewOptions(
            viewMode = AlbumViewMode.LIST_ART,
            gridSize = AlbumGridSize.LARGE,
            showMoreInfo = true,
            groupTracks = false,
            quickSearch = false,
            sortField = AlbumSortField.YEAR,
            ascending = false,
            trackSort = AlbumTrackSortOptions(AlbumTrackSortField.LAST_PLAYED, false)
        )
        try {
            preferences.save(changed)
            assertEquals(changed, MediaAlbumPreferences(compose.activity).load())
        } finally {
            preferences.save(previous)
        }
    }

    private fun selectCategory(category: MediaCategory) {
        val tag = "media-category:${category.name}"
        compose.onNodeWithTag("media-category-tabs").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }

    private fun chooseSort(field: AlbumSortField) {
        compose.onNodeWithTag("album-sort").performClick()
        compose.onNodeWithTag("album-sort-list").performScrollToNode(hasTestTag("album-sort:${field.name}"))
        compose.onNodeWithTag("album-sort:${field.name}").performClick()
        compose.onNodeWithTag("album-sort-confirm").performClick()
    }

    private fun chooseView(mode: AlbumViewMode) {
        compose.onNodeWithTag("album-settings").performClick()
        settingsRow("album-settings-view-mode").performClick()
        compose.onNodeWithText(compose.activity.getString(mode.labelRes)).performClick()
        compose.onNodeWithTag("album-settings-back").performClick()
    }

    private fun settingsRow(tag: String) = compose.onNodeWithTag("album-settings-list")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }

    private fun assertBefore(first: String, second: String) {
        val firstBounds = compose.onNodeWithTag("album-item:$first").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val secondBounds = compose.onNodeWithTag("album-item:$second").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("$first should appear before $second", firstBounds.top < secondBounds.top)
    }

    private fun assertGridColumns(columns: Int) {
        fun bounds(index: Int) = compose.onNodeWithTag("album-item:album ${index.toString().padStart(2, '0')}")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val first = bounds(1)
        val lastInRow = bounds(columns)
        val nextRow = bounds(columns + 1)
        assertEquals("The first row should contain $columns columns", first.top, lastInRow.top, 1f)
        assertTrue("The next item should wrap after $columns columns", nextRow.top > first.top)
        assertTrue("Items in one row must occupy distinct columns", lastInRow.left > first.left)
    }

    private fun showAlbums(options: MediaAlbumViewOptions = MediaAlbumViewOptions()): Harness {
        val directory = File(compose.activity.cacheDir, "album-page-test-${UUID.randomUUID()}").apply { mkdirs() }
        fixtureDirectory = directory
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("media-tags/preservation.m4a").use { it.readBytes() }
        val tracks = (1..3).map { index ->
            val file = File(directory, "Track $index.m4a").apply { writeBytes(fixture) }
            val album = if (index < 3) "Alpha" else "Beta"
            MediaAlbumTrack(
                track = MediaLibraryTrack(file.absolutePath, "Track $index", if (index < 3) "Singer A" else "Singer B", album,
                    durationMs = index * 60_000L),
                composer = "Composer $index",
                year = 2020 + index,
                trackNumber = index,
                modifiedAtMs = index * 1_000L
            )
        }
        val harness = Harness(buildMediaAlbums(tracks), mutableStateOf(options))
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaAlbumPageContent(
                        albums = harness.albums,
                        options = harness.options.value,
                        onOptionsChanged = { harness.options.value = it },
                        onPlay = { album, path -> harness.played += album.key to path }
                    )
                }
            }
        }
        return harness
    }

    private class Harness(
        val albums: List<MediaAlbum>,
        val options: MutableState<MediaAlbumViewOptions>,
        val played: MutableList<Pair<String, String?>> = mutableListOf()
    )
}
