package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.AlbumTrackSortField
import com.subtitleedit.library.MediaAlbum
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.SingerSortField
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
class MediaSingerPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixtureDirectory: File? = null

    @After fun removeFixture() { fixtureDirectory?.deleteRecursively() }

    @Test fun searchIsIndependentOfAlbumsAndIncludesOnlyIndexedPerformers() {
        showPage()
        compose.onNodeWithTag("album-search").performTextInput("Beta")
        selectCategory(MediaCategory.SINGERS)
        compose.onNodeWithTag("singer-item:").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.media_album_unknown_artist)).assertIsDisplayed()
        compose.onNodeWithTag("singer-item:not indexed singer").assertDoesNotExist()

        compose.onNodeWithTag("singer-search").performTextInput(" sInGeR a ")
        compose.onNodeWithTag("singer-item:singer a").assertIsDisplayed()
        compose.onNodeWithTag("singer-item:singer b").assertDoesNotExist()
        compose.onNodeWithTag("singer-item:").assertDoesNotExist()
        selectCategory(MediaCategory.ALBUMS)
        compose.onNodeWithTag("album-item:beta").assertIsDisplayed()
        compose.onNodeWithTag("album-item:alpha").assertDoesNotExist()
        selectCategory(MediaCategory.SINGERS)
        compose.onNodeWithTag("singer-item:singer a").assertIsDisplayed()
        compose.onNodeWithTag("singer-item:singer b").assertDoesNotExist()
    }

    @Test fun sortingKeepsDraftUntilConfirmAndRepeatedCriterionReversesOrder() {
        val harness = showPage()
        selectCategory(MediaCategory.SINGERS)
        assertSingerBefore("singer a", "singer b")
        val original = harness.singerOptions.value
        compose.onNodeWithTag("singer-sort").performClick()
        compose.onNodeWithTag("singer-sort:TRACK_COUNT").performClick()
        compose.onNodeWithTag("singer-sort:TRACK_COUNT").performClick()
        compose.runOnIdle { assertEquals(original, harness.singerOptions.value) }
        compose.onNodeWithTag("singer-sort-cancel").performClick()
        assertSingerBefore("singer a", "singer b")

        compose.onNodeWithTag("singer-sort").performClick()
        compose.onNodeWithTag("singer-sort:NAME").assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(original, harness.singerOptions.value) }
        compose.onNodeWithTag("singer-sort-confirm").performClick()
        compose.runOnIdle { assertFalse(harness.singerOptions.value.ascending) }
        assertSingerBefore("singer b", "singer a")

        compose.onNodeWithTag("singer-sort").performClick()
        compose.onNodeWithTag("singer-sort:TRACK_COUNT").performClick()
        compose.onNodeWithTag("singer-sort:TRACK_COUNT").performClick()
        compose.onNodeWithTag("singer-sort-confirm").performClick()
        compose.runOnIdle {
            assertEquals(SingerSortField.TRACK_COUNT, harness.singerOptions.value.sortField)
            assertTrue(harness.singerOptions.value.ascending)
        }
        assertSingerBefore("singer b", "singer a")
    }

    @Test fun moreInformationAndHiddenQuickSearchApplyOnlyToSingerPage() {
        val harness = showPage()
        selectCategory(MediaCategory.SINGERS)
        compose.onNodeWithTag("singer-info:singer a", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("singer-search").performTextInput("Singer A")
        compose.onNodeWithTag("singer-settings").performClick()
        setting("singer-settings-more-info").performClick()
        compose.onNodeWithTag("singer-more-info:TRACK_COUNT").performClick()
        setting("singer-settings-quick-search").performClick()
        compose.onNodeWithTag("singer-settings-back").performClick()

        compose.onNodeWithTag("singer-search").assertDoesNotExist()
        compose.onNodeWithTag("singer-item:singer b").assertIsDisplayed()
        compose.onNodeWithTag("singer-info:singer a", useUnmergedTree = true)
            .assertTextEquals(compose.activity.getString(R.string.media_album_detail_song_count, 3))
        compose.runOnIdle {
            assertEquals(SingerMoreInfo.TRACK_COUNT, harness.singerOptions.value.moreInfo)
            assertFalse(harness.singerOptions.value.quickSearch)
            assertTrue(harness.albumOptions.value.quickSearch)
        }
        selectCategory(MediaCategory.ALBUMS)
        compose.onNodeWithTag("album-search").assertIsDisplayed()
    }

    @Test fun albumFiltersAndUngroupedModeFeedTheSameVisiblePlaybackQueue() {
        val harness = showPage()
        openSinger()
        albumFilter("all").assertIsSelected()
        albumFilter("alpha").assertIsSelected()
        albumFilter("beta").assertIsSelected()
        albumFilter("all").performClick().assertIsNotSelected()
        albumFilter("alpha").assertIsNotSelected()
        albumFilter("beta").assertIsNotSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.media_album_detail_song_count, 0)).assertIsDisplayed()
        compose.onNodeWithTag("singer-play-all").assertIsNotEnabled()
        compose.onNodeWithTag("singer-tracks-shuffle").assertIsNotEnabled()
        harness.singerPaths.forEach { compose.onNodeWithTag("singer-track:$it").assertDoesNotExist() }
        albumFilter("all").performClick().assertIsSelected()
        compose.onNodeWithTag("singer-play-all").performClick()
        compose.runOnIdle { assertEquals(harness.singerPaths to null, harness.played.last()) }

        albumFilter("beta").performTouchInput { longClick() }
        albumFilter("alpha").assertIsNotSelected()
        albumFilter("all").assertIsNotSelected()
        albumFilter("beta").assertIsSelected().performClick().assertIsNotSelected()
        compose.onNodeWithTag("singer-play-all").assertIsNotEnabled()
        compose.onNodeWithTag("singer-tracks-shuffle").assertIsNotEnabled()
        albumFilter("beta").performTouchInput { longClick() }
        albumFilter("beta").assertIsSelected()
        compose.onNodeWithTag("singer-tracks-shuffle").performClick()
        val beta = harness.singerPaths.last()
        compose.runOnIdle { assertEquals(listOf(beta), harness.shuffled.last()) }
        track(beta).performClick()
        compose.runOnIdle { assertEquals(listOf(beta) to beta, harness.played.last()) }

        // A long press must also replace the selection when its target was not selected.
        albumFilter("alpha").performTouchInput { longClick() }
        albumFilter("beta").assertIsNotSelected()
        compose.onNodeWithTag("singer-play-all").performClick()
        compose.runOnIdle { assertEquals(harness.singerPaths.take(2) to null, harness.played.last()) }
        albumFilter("all").performClick().assertIsSelected()
        compose.onNodeWithTag("singer-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(harness.singerPaths, harness.shuffled.last()) }
        // Ordinary taps still add/remove individual filters.
        albumFilter("alpha").performClick().assertIsNotSelected()
        albumFilter("all").assertIsNotSelected()
        albumFilter("alpha").performClick().assertIsSelected()
        albumFilter("all").assertIsSelected()

        compose.onNodeWithTag("singer-detail-back").performClick()
        compose.onNodeWithTag("singer-settings").performClick()
        setting("singer-settings-group-tracks").performClick()
        compose.onNodeWithTag("singer-settings-back").performClick()
        compose.onNodeWithTag("singer-item:singer a").performClick()
        compose.onNodeWithTag("singer-albums").assertDoesNotExist()
        compose.onNodeWithTag("singer-play-all").performClick()
        compose.runOnIdle { assertEquals(harness.singerPaths to null, harness.played.last()) }
    }

    @Test fun longPressActionsUseSelectedFilesAndBackClearsSelectionBeforeLeaving() {
        val harness = showPage(MediaSingerViewOptions(groupTracks = false))
        openSinger()
        val first = harness.singerPaths.first()
        track(first).performTouchInput { longClick() }
        selectionAction(R.string.player_action_favorite)
        compose.runOnIdle {
            assertEquals(listOf(first), harness.favorited.single())
            assertTrue(harness.played.isEmpty())
        }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()

        track(first).performTouchInput { longClick() }
        selectionAction(R.string.media_lib_files_info)
        compose.runOnIdle { assertEquals(listOf(first), harness.information) }
        selectionAction(R.string.player_action_add_to_playlist)
        compose.runOnIdle { assertEquals(listOf(first), harness.playlisted.single()) }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()

        track(first).performTouchInput { longClick() }
        pressBack()
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        compose.onNodeWithTag("singer-detail").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("singer-detail").assertDoesNotExist()
        compose.onNodeWithTag("singer-list").assertIsDisplayed()
    }

    @Test fun confirmedAlbumAndTrackSortControlTheFiltersAndVisibleQueue() {
        val harness = showPage()
        selectCategory(MediaCategory.SINGERS)
        compose.onNodeWithTag("singer-settings").performClick()
        setting("singer-settings-album-sort").performClick()
        compose.onNodeWithTag("album-sort:NAME").performClick()
        compose.runOnIdle { assertTrue(harness.singerOptions.value.albumAscending) }
        compose.onNodeWithTag("album-sort-confirm").performClick()
        compose.onNodeWithTag("singer-settings-back").performClick()
        compose.onNodeWithTag("singer-item:singer a").performClick()
        albumFilter("all").assertIsSelected()
        albumFilter("beta").assertIsSelected().performTouchInput { longClick() }
        compose.onNodeWithTag("singer-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(listOf(harness.singerPaths.last()), harness.shuffled.last()) }

        albumFilter("all").performClick()
        compose.onNodeWithTag("singer-tracks-sort").performClick()
        compose.onNodeWithTag("album-track-sort-list").performScrollToNode(hasTestTag("album-track-sort:DURATION"))
        compose.onNodeWithTag("album-track-sort:DURATION").performClick()
        compose.onNodeWithTag("album-track-sort:DURATION").performClick()
        compose.runOnIdle { assertEquals(AlbumTrackSortField.TRACK_NUMBER, harness.singerOptions.value.trackSort.field) }
        compose.onNodeWithTag("album-track-sort-confirm").performClick()
        compose.onNodeWithTag("singer-tracks-shuffle").performClick()
        compose.runOnIdle {
            assertEquals(AlbumSortField.NAME, harness.singerOptions.value.albumSortField)
            assertFalse(harness.singerOptions.value.albumAscending)
            assertEquals(harness.singerPaths.reversed(), harness.shuffled.last())
        }
    }

    private fun selectCategory(category: MediaCategory) {
        val tag = "media-category:${category.name}"
        compose.onNodeWithTag("media-category-tabs").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun openSinger() {
        selectCategory(MediaCategory.SINGERS)
        compose.onNodeWithTag("singer-item:singer a").performClick()
    }

    private fun setting(tag: String) = compose.onNodeWithTag("singer-settings-list")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }

    private fun track(path: String) = compose.onNodeWithTag("singer-tracks")
        .performScrollToNode(hasTestTag("singer-track:$path")).let { compose.onNodeWithTag("singer-track:$path") }

    private fun albumFilter(key: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val tag = if (key == "all") "singer-album-all" else "singer-album:$key"
        compose.onNodeWithTag("singer-tracks").performScrollToNode(hasTestTag("singer-albums"))
        compose.onNodeWithTag("singer-albums").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun selectionAction(label: Int) {
        compose.onNodeWithTag("library-selection-options").performClick()
        compose.onNodeWithText(compose.activity.getString(label)).performClick()
    }

    private fun assertSingerBefore(first: String, second: String) {
        val firstTop = compose.onNodeWithTag("singer-item:$first").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithTag("singer-item:$second").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue("$first should appear before $second", firstTop < secondTop)
    }

    private fun showPage(options: MediaSingerViewOptions = MediaSingerViewOptions()): Harness {
        val root = File(compose.activity.cacheDir, "singer-page-test-${UUID.randomUUID()}").apply { mkdirs() }
        fixtureDirectory = root
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("media-tags/preservation.m4a").use { it.readBytes() }
        val tracks = (1..5).map { index ->
            val file = File(root, "Track $index.m4a").apply { writeBytes(fixture) }
            MediaAlbumTrack(
                track = MediaLibraryTrack(file.path, "Track $index", when (index) {
                    4 -> "Singer B"
                    5 -> ""
                    else -> "Singer A"
                }, when (index) {
                    1, 2 -> "Alpha"
                    3 -> "Beta"
                    4 -> "Gamma"
                    else -> "Delta"
                }, durationMs = index * 60_000L),
                albumArtist = "Separate artist", trackNumber = index,
                modifiedAtMs = index * 1_000L, year = 2020 + index
            )
        }
        // A readable file outside the passed indexed catalog must not appear in singer browsing.
        File(root, "Not indexed singer.m4a").writeBytes(fixture)
        val harness = Harness(buildMediaAlbums(tracks), tracks.take(3).map { it.track.path }, mutableStateOf(options))
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaAlbumPageContent(
                        albums = harness.albums,
                        options = harness.albumOptions.value,
                        onOptionsChanged = { harness.albumOptions.value = it },
                        singerOptions = harness.singerOptions.value,
                        onSingerOptionsChanged = { harness.singerOptions.value = it },
                        onPlay = { album, clicked -> harness.played += album.tracks.map { it.track.path } to clicked },
                        onShuffle = { album -> harness.shuffled += album.tracks.map { it.track.path } },
                        onFavoriteSelection = { selected, done -> harness.favorited += selected.map { it.path }; done() },
                        onAddSelectionToPlaylist = { selected, done -> harness.playlisted += selected.map { it.path }; done() },
                        onShowInfo = { harness.information += it }
                    )
                }
            }
        }
        return harness
    }

    private class Harness(
        val albums: List<MediaAlbum>,
        val singerPaths: List<String>,
        val singerOptions: MutableState<MediaSingerViewOptions>
    ) {
        val albumOptions = mutableStateOf(MediaAlbumViewOptions())
        val played = mutableListOf<Pair<List<String>, String?>>()
        val shuffled = mutableListOf<List<String>>()
        val favorited = mutableListOf<List<String>>()
        val playlisted = mutableListOf<List<String>>()
        val information = mutableListOf<String>()
    }
}
