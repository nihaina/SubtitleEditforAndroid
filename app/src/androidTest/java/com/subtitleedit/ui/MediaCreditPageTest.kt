package com.subtitleedit.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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

/** Artist and composer tabs must use their own metadata, presentation state and action queues. */
@RunWith(AndroidJUnit4::class)
class MediaCreditPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixtureDirectory: File? = null

    @After fun removeFixture() { fixtureDirectory?.deleteRecursively() }

    @Test fun categoriesUseSeparateMetadataAndRetainIndependentSearchQueries() {
        showPage()
        compose.onNodeWithTag("album-search").performTextInput("Beta")
        selectCategory(MediaCategory.ARTISTS)
        compose.onNodeWithTag("artist-item:").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.media_album_unknown_album_artist)).assertIsDisplayed()
        compose.onNodeWithTag("artist-item:singer a").assertDoesNotExist()
        compose.onNodeWithTag("artist-item:not indexed artist").assertDoesNotExist()
        captureIfRequested("credit-artist-page.png")
        compose.onNodeWithTag("artist-search").performTextInput(" aRtIsT a ")
        compose.onNodeWithTag("artist-item:artist a").assertIsDisplayed()
        compose.onNodeWithTag("artist-item:artist b").assertDoesNotExist()

        selectCategory(MediaCategory.COMPOSERS)
        compose.onNodeWithTag("composer-item:").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.media_album_unknown_composer)).assertIsDisplayed()
        compose.onNodeWithTag("composer-item:composer b").assertIsDisplayed()
        compose.onNodeWithTag("composer-item:artist a").assertDoesNotExist()
        captureIfRequested("credit-composer-page.png")
        compose.onNodeWithTag("composer-search").performTextInput(" cOmPoSeR a ")
        compose.onNodeWithTag("composer-item:composer a").assertIsDisplayed()
        compose.onNodeWithTag("composer-item:composer b").assertDoesNotExist()

        selectCategory(MediaCategory.ARTISTS)
        compose.onNodeWithTag("artist-item:artist a").assertIsDisplayed()
        compose.onNodeWithTag("artist-item:artist b").assertDoesNotExist()
        selectCategory(MediaCategory.ALBUMS)
        compose.onNodeWithTag("album-item:beta").assertIsDisplayed()
        compose.onNodeWithTag("album-item:alpha").assertDoesNotExist()
        selectCategory(MediaCategory.SINGERS)
        compose.onNodeWithTag("singer-item:singer b").assertIsDisplayed()
    }

    @Test fun refreshedTagsDoNotOpenAnUnrelatedCategoryWithTheSameName() {
        val harness = showPage()
        val sharedNames = harness.tracks.mapIndexed { index, track ->
            if (index == 0) track.copy(composer = "Artist A") else track
        }
        compose.runOnIdle { harness.albums.value = buildMediaAlbums(sharedNames) }
        selectCategory(MediaCategory.ARTISTS)
        compose.onNodeWithTag("artist-item:artist a").performClick()
        compose.onNodeWithTag("artist-detail").assertIsDisplayed()

        compose.runOnIdle {
            harness.albums.value = buildMediaAlbums(sharedNames.map { track ->
                if (track.albumArtist == "Artist A") track.copy(albumArtist = "Renamed") else track
            })
        }
        compose.onNodeWithTag("artist-list").assertIsDisplayed()
        compose.onNodeWithTag("artist-detail").assertDoesNotExist()
        selectCategory(MediaCategory.COMPOSERS)
        compose.onNodeWithTag("composer-list").assertIsDisplayed()
        compose.onNodeWithTag("composer-item:artist a").assertIsDisplayed()
        compose.onNodeWithTag("composer-detail").assertDoesNotExist()

        compose.runOnIdle { harness.albums.value = buildMediaAlbums(sharedNames) }
        selectCategory(MediaCategory.ARTISTS)
        compose.onNodeWithTag("artist-list").assertIsDisplayed()
        compose.onNodeWithTag("artist-item:artist a").assertIsDisplayed()
        compose.onNodeWithTag("artist-detail").assertDoesNotExist()
    }

    @Test fun artistAndComposerSortsKeepDraftsUntilConfirmationAndStayIndependent() {
        val harness = showPage()
        listOf(MediaCategory.ARTISTS to "artist", MediaCategory.COMPOSERS to "composer").forEach { (category, prefix) ->
            selectCategory(category)
            val state = if (prefix == "artist") harness.artistOptions else harness.composerOptions
            val original = state.value
            assertBefore(prefix, "$prefix a", "$prefix b")
            compose.onNodeWithTag("$prefix-sort").performClick()
            compose.onNodeWithTag("$prefix-sort:DURATION").performClick()
            compose.onNodeWithTag("$prefix-sort:DURATION").performClick()
            compose.runOnIdle { assertEquals(original, state.value) }
            compose.onNodeWithTag("$prefix-sort-cancel").performClick()
            assertBefore(prefix, "$prefix a", "$prefix b")

            compose.onNodeWithTag("$prefix-sort").performClick()
            compose.onNodeWithTag("$prefix-sort:NAME").assertIsSelected().performClick()
            compose.onNodeWithTag("$prefix-sort-confirm").performClick()
            compose.runOnIdle { assertFalse(state.value.ascending) }
            assertBefore(prefix, "$prefix b", "$prefix a")
            listOf(SingerSortField.TRACK_COUNT, SingerSortField.DURATION, SingerSortField.UPDATED_AT).forEach { field ->
                val before = state.value
                compose.onNodeWithTag("$prefix-sort").performClick()
                compose.onNodeWithTag("$prefix-sort:${field.name}").performClick()
                compose.runOnIdle { assertEquals(before, state.value) }
                compose.onNodeWithTag("$prefix-sort-confirm").performClick()
                compose.runOnIdle {
                    assertEquals(field, state.value.sortField)
                    assertFalse(state.value.ascending)
                }
                assertBefore(prefix, "$prefix a", "$prefix b")
            }
            if (prefix == "artist") compose.runOnIdle {
                assertEquals(MediaSingerViewOptions(), harness.composerOptions.value)
                assertEquals(MediaSingerViewOptions(), harness.singerOptions.value)
            }
        }
    }

    @Test fun settingsApplyCompactInformationAndSearchOptionsOnlyToTheirOwnCategory() {
        val harness = showPage()
        selectCategory(MediaCategory.ARTISTS)
        compose.onNodeWithTag("artist-search").performTextInput("Artist A")
        compose.onNodeWithTag("artist-settings").performClick()
        captureIfRequested("credit-artist-settings.png")
        setting("artist", "more-info").performClick()
        compose.onNodeWithTag("artist-more-info:TRACK_COUNT").performClick()
        setting("artist", "group-tracks").performClick()
        setting("artist", "quick-search").performClick()
        compose.onNodeWithTag("artist-settings-back").performClick()
        compose.onNodeWithTag("artist-search").assertDoesNotExist()
        compose.onNodeWithTag("artist-item:artist b").assertIsDisplayed()
        compose.onNodeWithTag("artist-info:artist a", useUnmergedTree = true)
            .assertTextEquals(compose.activity.getString(R.string.media_album_detail_song_count, 3))
        compose.runOnIdle {
            assertFalse(harness.artistOptions.value.quickSearch)
            assertFalse(harness.artistOptions.value.groupTracks)
            assertEquals(SingerMoreInfo.TRACK_COUNT, harness.artistOptions.value.moreInfo)
            assertEquals(MediaSingerViewOptions(), harness.composerOptions.value)
            assertEquals(MediaSingerViewOptions(), harness.singerOptions.value)
        }

        selectCategory(MediaCategory.COMPOSERS)
        compose.onNodeWithTag("composer-search").assertIsDisplayed()
        compose.onNodeWithTag("composer-info:composer a", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("composer-settings").performClick()
        val infoBounds = setting("composer", "more-info").fetchSemanticsNode().boundsInRoot
        val groupingBounds = compose.onNodeWithTag("composer-settings-group-tracks").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Settings rows should not acquire large vertical gaps", groupingBounds.top - infoBounds.bottom <= 8f * density)
        setting("composer", "more-info").performClick()
        compose.onNodeWithTag("composer-more-info:DURATION").performClick()
        compose.onNodeWithTag("composer-settings-back").performClick()
        compose.runOnIdle {
            assertEquals(SingerMoreInfo.DURATION, harness.composerOptions.value.moreInfo)
            assertEquals(SingerMoreInfo.TRACK_COUNT, harness.artistOptions.value.moreInfo)
        }
    }

    @Test fun artistDetailUsesItsAudioVideoMembersForPlaybackAndLongPressActions() {
        val harness = showPage(artistOptions = MediaSingerViewOptions(groupTracks = false))
        selectCategory(MediaCategory.ARTISTS)
        compose.onNodeWithTag("artist-item:artist a").performClick()
        val expected = harness.paths(listOf(1, 3, 4))
        compose.onNodeWithTag("artist-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(expected, harness.shuffled.single()) }
        val video = harness.path(4)
        track("artist", video).performClick()
        compose.runOnIdle { assertEquals(expected to video, harness.played.single()) }

        val first = harness.path(1)
        track("artist", first).performTouchInput { longClick() }
        selectionAction(R.string.player_action_favorite)
        compose.runOnIdle { assertEquals(listOf(first), harness.favorited.single()) }
        track("artist", video).performTouchInput { longClick() }
        selectionAction(R.string.player_action_add_to_playlist)
        compose.runOnIdle { assertEquals(listOf(video), harness.playlisted.single()) }
        track("artist", first).performTouchInput { longClick() }
        selectionAction(R.string.media_lib_files_info)
        compose.runOnIdle { assertEquals(listOf(first), harness.information) }
        pressBack()
        compose.onNodeWithTag("artist-detail").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("artist-list").assertIsDisplayed()
    }

    @Test fun composerAlbumFiltersAndConfirmedTrackSortControlTheActualVisibleQueue() {
        val harness = showPage()
        selectCategory(MediaCategory.COMPOSERS)
        compose.onNodeWithTag("composer-item:composer a").performClick()
        albumFilter("composer", "all").assertIsSelected()
        albumFilter("composer", "alpha").assertIsSelected()
        albumFilter("composer", "beta").assertIsSelected()
        compose.onNodeWithTag("composer-play-all").performClick()
        compose.runOnIdle { assertEquals(harness.paths(listOf(2, 3, 4)) to null, harness.played.single()) }
        albumFilter("composer", "beta").performTouchInput { longClick() }
        albumFilter("composer", "all").assertIsNotSelected()
        albumFilter("composer", "alpha").assertIsNotSelected()
        compose.onNodeWithTag("composer-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(harness.paths(listOf(2, 3)), harness.shuffled.last()) }

        compose.onNodeWithTag("composer-tracks-sort").performClick()
        compose.onNodeWithTag("album-track-sort-list").performScrollToNode(hasTestTag("album-track-sort:DURATION"))
        compose.onNodeWithTag("album-track-sort:DURATION").performClick()
        compose.onNodeWithTag("album-track-sort:DURATION").performClick()
        compose.runOnIdle { assertEquals(AlbumTrackSortField.TRACK_NUMBER, harness.composerOptions.value.trackSort.field) }
        compose.onNodeWithTag("album-track-sort-confirm").performClick()
        compose.onNodeWithTag("composer-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(harness.paths(listOf(3, 2)), harness.shuffled.last()) }
        track("composer", harness.path(3)).performClick()
        compose.runOnIdle { assertEquals(harness.paths(listOf(3, 2)) to harness.path(3), harness.played.last()) }

        albumFilter("composer", "all").performClick().assertIsSelected()
        compose.onNodeWithTag("composer-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(harness.paths(listOf(4, 3, 2)), harness.shuffled.last()) }
        albumFilter("composer", "all").performClick().assertIsNotSelected()
        albumFilter("composer", "alpha").assertIsNotSelected()
        albumFilter("composer", "beta").assertIsNotSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.media_album_detail_song_count, 0)).assertIsDisplayed()
        compose.onNodeWithTag("composer-play-all").assertIsNotEnabled()
        compose.onNodeWithTag("composer-tracks-shuffle").assertIsNotEnabled()
        harness.paths(listOf(2, 3, 4)).forEach { compose.onNodeWithTag("composer-track:$it").assertDoesNotExist() }
        albumFilter("composer", "all").performClick().assertIsSelected()
        compose.onNodeWithTag("composer-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(harness.paths(listOf(4, 3, 2)), harness.shuffled.last()) }
    }

    @Test fun artistAlbumFiltersDefaultToAllAndLongPressExcludesEveryOtherAlbum() {
        val harness = showPage()
        selectCategory(MediaCategory.ARTISTS)
        compose.onNodeWithTag("artist-item:artist a").performClick()
        albumFilter("artist", "all").assertIsSelected()
        albumFilter("artist", "alpha").assertIsSelected()
        albumFilter("artist", "beta").assertIsSelected()
        compose.onNodeWithTag("artist-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(harness.paths(listOf(1, 3, 4)), harness.shuffled.single()) }
        albumFilter("artist", "all").performClick().assertIsNotSelected()
        albumFilter("artist", "alpha").assertIsNotSelected()
        albumFilter("artist", "beta").assertIsNotSelected()
        compose.onNodeWithTag("artist-play-all").assertIsNotEnabled()
        compose.onNodeWithTag("artist-tracks-shuffle").assertIsNotEnabled()
        // A long press can select a single album directly from the empty state.
        albumFilter("artist", "alpha").performTouchInput { longClick() }
        albumFilter("artist", "beta").assertIsNotSelected()
        albumFilter("artist", "all").assertIsNotSelected()
        compose.onNodeWithTag("artist-play-all").performClick()
        compose.runOnIdle { assertEquals(harness.paths(listOf(1, 4)) to null, harness.played.last()) }
        albumFilter("artist", "all").performClick().assertIsSelected()
        compose.onNodeWithTag("artist-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(harness.paths(listOf(1, 3, 4)), harness.shuffled.last()) }
    }

    private fun selectCategory(category: MediaCategory) {
        val tag = "media-category:${category.name}"
        compose.onNodeWithTag("media-category-tabs").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun setting(prefix: String, name: String) = compose.onNodeWithTag("$prefix-settings-list")
        .performScrollToNode(hasTestTag("$prefix-settings-$name"))
        .let { compose.onNodeWithTag("$prefix-settings-$name") }

    private fun track(prefix: String, path: String) = compose.onNodeWithTag("$prefix-tracks")
        .performScrollToNode(hasTestTag("$prefix-track:$path"))
        .let { compose.onNodeWithTag("$prefix-track:$path") }

    private fun albumFilter(prefix: String, key: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val tag = if (key == "all") "$prefix-album-all" else "$prefix-album:$key"
        compose.onNodeWithTag("$prefix-tracks").performScrollToNode(hasTestTag("$prefix-albums"))
        compose.onNodeWithTag("$prefix-albums").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun selectionAction(label: Int) {
        compose.onNodeWithTag("library-selection-options").performClick()
        compose.onNodeWithText(compose.activity.getString(label)).performClick()
    }

    private fun assertBefore(prefix: String, first: String, second: String) {
        val firstTop = compose.onNodeWithTag("$prefix-item:$first").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithTag("$prefix-item:$second").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue("$first should appear before $second", firstTop < secondTop)
    }

    private fun captureIfRequested(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("creditScreenshot") != "true") return
        compose.waitForIdle()
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { screenshot.recycle() }
    }

    private fun showPage(artistOptions: MediaSingerViewOptions = MediaSingerViewOptions()): Harness {
        val root = File(compose.activity.cacheDir, "credit-page-test-${UUID.randomUUID()}").apply { mkdirs() }
        fixtureDirectory = root
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("media-tags/preservation.m4a").use { it.readBytes() }
        val tracks = (1..5).map { index ->
            val file = File(root, "Track $index.${if (index == 4) "mp4" else "m4a"}").apply { writeBytes(fixture) }
            MediaAlbumTrack(
                track = MediaLibraryTrack(
                    file.path, "Track $index", artist = when (index) { 1, 2 -> "Singer A"; 3, 4 -> "Singer B"; else -> "" },
                    album = when (index) { 1, 4 -> "Alpha"; 2, 3 -> "Beta"; else -> "Delta" },
                    isVideo = index == 4, durationMs = listOf(60_000L, 90_000L, 150_000L, 240_000L, 30_000L)[index - 1]
                ),
                albumArtist = when (index) { 1, 3, 4 -> "Artist A"; 2 -> "Artist B"; else -> "" },
                composer = when (index) { 2, 3, 4 -> "Composer A"; 1 -> "Composer B"; else -> "" },
                trackNumber = index, modifiedAtMs = index * 1_000L, year = 2020 + index
            )
        }
        File(root, "Not indexed artist.m4a").writeBytes(fixture)
        val harness = Harness(tracks, artistOptions)
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaAlbumPageContent(
                        albums = harness.albums.value,
                        options = harness.albumOptions.value,
                        onOptionsChanged = { harness.albumOptions.value = it },
                        singerOptions = harness.singerOptions.value,
                        onSingerOptionsChanged = { harness.singerOptions.value = it },
                        artistOptions = harness.artistOptions.value,
                        onArtistOptionsChanged = { harness.artistOptions.value = it },
                        composerOptions = harness.composerOptions.value,
                        onComposerOptionsChanged = { harness.composerOptions.value = it },
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

    private class Harness(val tracks: List<MediaAlbumTrack>, artistInitial: MediaSingerViewOptions) {
        val albums = mutableStateOf(buildMediaAlbums(tracks))
        val albumOptions = mutableStateOf(MediaAlbumViewOptions())
        val singerOptions = mutableStateOf(MediaSingerViewOptions())
        val artistOptions = mutableStateOf(artistInitial)
        val composerOptions = mutableStateOf(MediaSingerViewOptions())
        val played = mutableListOf<Pair<List<String>, String?>>()
        val shuffled = mutableListOf<List<String>>()
        val favorited = mutableListOf<List<String>>()
        val playlisted = mutableListOf<List<String>>()
        val information = mutableListOf<String>()
        fun path(index: Int): String = tracks[index - 1].track.path
        fun paths(indices: List<Int>): List<String> = indices.map(::path)
    }
}
