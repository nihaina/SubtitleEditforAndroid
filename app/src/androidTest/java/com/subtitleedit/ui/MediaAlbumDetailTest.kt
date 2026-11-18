package com.subtitleedit.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAlbumDetailTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixtureDirectory: File? = null

    @After fun removeFixture() { fixtureDirectory?.deleteRecursively() }

    @Test fun categoryDropdownStaysAboveFilesAndChangesTheDisplayedMembership() {
        val harness = showDetail(4, separateSingers = true)
        val selector = compose.onNodeWithTag("album-group-selector").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val playAll = compose.onNodeWithTag("album-play-all").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val shuffle = compose.onNodeWithTag("album-tracks-shuffle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val sort = compose.onNodeWithTag("album-tracks-sort").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(selector.top < shuffle.top)
        assertTrue(selector.left < shuffle.left)
        assertTrue(selector.left < playAll.left && playAll.left < shuffle.left)
        assertTrue(shuffle.left < sort.left)
        row(harness.tracks.first()).assertIsDisplayed()
        val all = compose.onNodeWithTag("album-group-all").assertIsDisplayed().assertIsSelected()
        val firstGroup = compose.onNodeWithTag("album-group:Singer A").assertIsSelected()
        assertTrue("Select all is the leftmost group card",
            all.fetchSemanticsNode().boundsInRoot.right <= firstGroup.fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithTag("album-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(harness.paths, harness.shuffled.single()) }
        firstGroup.performTouchInput { longClick() }
        all.assertIsNotSelected()

        compose.onNodeWithTag("album-group-selector").performClick()
        compose.onNodeWithTag("album-grouping:COMPOSERS").performClick()
        all.assertIsSelected()
        compose.onNodeWithTag("album-group:Composer").assertIsDisplayed()
        compose.onNodeWithTag("album-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(listOf(harness.paths, harness.paths), harness.shuffled) }
    }

    @Test fun longPressingAGroupExclusivelySelectsItAndSelectAllRestoresTheFullQueue() {
        val harness = showDetail(4, separateSingers = true)
        val groups = compose.onNodeWithTag("album-groups")
        val all = compose.onNodeWithTag("album-group-all")
        val singerA = compose.onNodeWithTag("album-group:Singer A")
        val singerB = compose.onNodeWithTag("album-group:Singer B")
        val firstSingerPaths = harness.tracks.filter { it.track.artist == "Singer A" }.map { it.track.path }
        val secondSingerPaths = harness.tracks.filter { it.track.artist == "Singer B" }.map { it.track.path }

        // A is already selected by default. Long press must isolate it without also toggling it off.
        singerA.assertIsSelected().performTouchInput { longClick() }
        singerA.assertIsSelected()
        all.assertIsNotSelected()
        groups.performScrollToNode(hasTestTag("album-group:Singer B"))
        singerB.assertIsNotSelected()
        compose.onNodeWithTag("album-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(firstSingerPaths, harness.shuffled.last()) }

        // Long press also works on an unselected group and excludes the prior selection.
        singerB.performTouchInput { longClick() }
        singerB.assertIsSelected()
        groups.performScrollToNode(hasTestTag("album-group:Singer A"))
        singerA.assertIsNotSelected()
        row(harness.tracks.first()).assertDoesNotExist()
        compose.onNodeWithTag("album-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(secondSingerPaths, harness.shuffled.last()) }

        groups.performScrollToNode(hasTestTag("album-group-all"))
        all.performClick().assertIsSelected()
        singerA.assertIsSelected()
        groups.performScrollToNode(hasTestTag("album-group:Singer B"))
        singerB.assertIsSelected()
        compose.onNodeWithTag("album-tracks-shuffle").performClick()
        compose.runOnIdle {
            assertEquals(listOf(firstSingerPaths, secondSingerPaths, harness.paths), harness.shuffled)
            assertTrue("Filtering groups must not start playback", harness.played.isEmpty())
        }
    }

    @Test fun selectAllTogglesBetweenEveryGroupAndNoGroupsAndLongPressCanRestartSelection() {
        val harness = showDetail(4, separateSingers = true)
        val groups = compose.onNodeWithTag("album-groups")
        val all = compose.onNodeWithTag("album-group-all")
        val singerA = compose.onNodeWithTag("album-group:Singer A")
        val singerB = compose.onNodeWithTag("album-group:Singer B")
        all.assertIsSelected().performClick().assertIsNotSelected()
        singerA.assertIsNotSelected()
        groups.performScrollToNode(hasTestTag("album-group:Singer B"))
        singerB.assertIsNotSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.media_album_detail_song_count, 0)).assertIsDisplayed()
        compose.onNodeWithTag("album-play-all").assertIsNotEnabled()
        compose.onNodeWithTag("album-tracks-shuffle").assertIsNotEnabled()
        row(harness.tracks.first()).assertDoesNotExist()

        groups.performScrollToNode(hasTestTag("album-group-all"))
        all.performClick().assertIsSelected()
        compose.onNodeWithTag("album-play-all").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(harness.paths to null, harness.played.single()) }
        all.performClick().assertIsNotSelected()
        groups.performScrollToNode(hasTestTag("album-group:Singer B"))
        singerB.performTouchInput { longClick() }
        singerB.assertIsSelected()
        groups.performScrollToNode(hasTestTag("album-group:Singer A"))
        singerA.assertIsNotSelected()
        compose.onNodeWithTag("album-tracks-shuffle").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(harness.tracks.filter { it.track.artist == "Singer B" }.map { it.track.path }, harness.shuffled.single())
        }
    }

    @Test fun longPressAdvancedSelectionAndActionsUseSelectedFilesWithoutPlaying() {
        val harness = showDetail(4)
        row(harness.tracks[0]).performTouchInput { longClick() }
        compose.onNodeWithTag("album-tracks").performScrollToNode(hasTestTag("album-track:${harness.paths[2]}"))
        row(harness.tracks[2]).performClick()
        compose.onNodeWithTag("library-selection-advanced").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.media_lib_selection_range)).performClick()
        compose.onNodeWithTag("library-selection-options").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.player_action_favorite)).performClick()
        compose.runOnIdle {
            assertEquals(harness.paths.take(3), harness.favorited.single())
            assertTrue(harness.played.isEmpty())
        }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()

        row(harness.tracks[1]).performTouchInput { longClick() }
        compose.onNodeWithTag("library-selection-options").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.media_lib_files_info)).performClick()
        compose.runOnIdle { assertEquals(listOf(harness.paths[1]), harness.information) }
        compose.onNodeWithTag("library-selection-options").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.player_action_add_to_playlist)).performClick()
        compose.runOnIdle { assertEquals(listOf(harness.paths[1]), harness.playlisted.single()) }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()

        row(harness.tracks[0]).performTouchInput { longClick() }
        pressBack()
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, harness.backRequests) }
        compose.onNodeWithTag("album-detail-back").performClick()
        compose.runOnIdle { assertEquals(1, harness.backRequests) }
    }

    @Test fun confirmedTrackSortControlsTheShuffleAndSingleTrackQueues() {
        val harness = showDetail(4, grouped = false)
        compose.onNodeWithTag("album-tracks-sort").performClick()
        compose.onNodeWithTag("album-track-sort-list").performScrollToNode(hasTestTag("album-track-sort:DURATION"))
        compose.onNodeWithTag("album-track-sort:DURATION").performClick()
        compose.onNodeWithTag("album-track-sort:DURATION").performClick()
        compose.runOnIdle { assertEquals(AlbumTrackSortField.TRACK_NUMBER, harness.sort.value.field) }
        compose.onNodeWithTag("album-track-sort-confirm").performClick()
        compose.runOnIdle {
            assertEquals(AlbumTrackSortField.DURATION, harness.sort.value.field)
            assertTrue(!harness.sort.value.ascending)
        }
        compose.onNodeWithTag("album-tracks-shuffle").performClick()
        val expected = harness.paths.reversed()
        compose.runOnIdle { assertEquals(expected, harness.shuffled.single()) }
        compose.onNodeWithTag("album-tracks").performScrollToNode(hasTestTag("album-track:${expected.first()}"))
        compose.onNodeWithTag("album-track:${expected.first()}").performClick()
        compose.runOnIdle { assertEquals(expected to expected.first(), harness.played.single()) }
    }

    @Test fun landscapeLastFileScrollsAboveTheExpandedSelectionPanel() {
        val previous = compose.activity.requestedOrientation
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5_000L) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        try {
            val harness = showDetail(24, grouped = false)
            compose.onNodeWithTag("album-tracks").performScrollToNode(hasTestTag("album-track:${harness.paths.first()}"))
            row(harness.tracks.first()).performTouchInput { longClick() }
            compose.onNodeWithTag("library-selection-advanced").performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.media_lib_selection_all)).performClick()
            compose.onNodeWithTag("library-selection-expand").performClick()
            compose.onNodeWithTag("album-tracks").performScrollToNode(hasTestTag("album-track:${harness.paths.last()}"))
            val last = row(harness.tracks.last()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val panel = compose.onNodeWithTag("library-selection-panel").fetchSemanticsNode().boundsInRoot
            assertTrue("The last file must remain selectable above the panel", last.bottom <= panel.top + 1f)
            compose.onNodeWithTag("library-selection-options").assertIsDisplayed()
            compose.onNodeWithTag("library-selection-advanced").assertIsDisplayed()
            compose.onNodeWithTag("library-selection-cancel").assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = previous }
        }
    }

    private fun row(track: MediaAlbumTrack) = compose.onNodeWithTag("album-track:${track.track.path}")

    private fun showDetail(count: Int, grouped: Boolean = true, separateSingers: Boolean = false): Harness {
        val root = File(compose.activity.cacheDir, "album-detail-test-${UUID.randomUUID()}").apply { mkdirs() }
        fixtureDirectory = root
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("media-tags/preservation.m4a").use { it.readBytes() }
        val tracks = (1..count).map { index ->
            val file = File(root, "Track ${index.toString().padStart(2, '0')}.m4a").apply { writeBytes(fixture) }
            MediaAlbumTrack(
                track = MediaLibraryTrack(file.path, file.name, if (separateSingers && index > 2) "Singer B" else "Singer A",
                    "Fixture album", durationMs = index * 1_000L),
                composer = "Composer", albumArtist = "Album artist", trackNumber = index
            )
        }
        val harness = Harness(buildMediaAlbums(tracks).single())
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaAlbumDetailContent(
                        album = harness.album,
                        groupTracks = grouped,
                        sortField = AlbumSortField.NAME,
                        ascending = true,
                        trackSort = harness.sort.value,
                        onTrackSortChanged = { harness.sort.value = it },
                        isActive = true,
                        onBack = { harness.backRequests++ },
                        onPlay = { shown, clicked -> harness.played += shown.map { it.track.path } to clicked },
                        onShuffle = { shown -> harness.shuffled += shown.map { it.track.path } },
                        onFavoriteSelection = { selected, done -> harness.favorited += selected.map { it.path }; done() },
                        onAddSelectionToPlaylist = { selected, done -> harness.playlisted += selected.map { it.path }; done() },
                        onShowInfo = { harness.information += it }
                    )
                }
            }
        }
        return harness
    }

    private class Harness(val album: MediaAlbum) {
        val tracks = album.tracks
        val paths = tracks.map { it.track.path }
        val sort: MutableState<AlbumTrackSortOptions> = mutableStateOf(AlbumTrackSortOptions())
        val played = mutableListOf<Pair<List<String>, String?>>()
        val shuffled = mutableListOf<List<String>>()
        val favorited = mutableListOf<List<String>>()
        val playlisted = mutableListOf<List<String>>()
        val information = mutableListOf<String>()
        var backRequests = 0
    }
}
