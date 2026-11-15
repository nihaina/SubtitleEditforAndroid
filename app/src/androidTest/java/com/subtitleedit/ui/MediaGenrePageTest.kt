package com.subtitleedit.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import com.subtitleedit.library.AlbumTrackSortField
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

/** Genres use indexed genre tags, with independent presentation and the shared track actions. */
@RunWith(AndroidJUnit4::class)
class MediaGenrePageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixtureDirectory: File? = null

    @After fun removeFixture() { fixtureDirectory?.deleteRecursively() }

    @Test fun genreNamesAndSearchUseOnlyWholeGenreTagsAndKeepQueriesIndependent() {
        showPage()
        selectCategory(MediaCategory.GENRES)
        compose.onNodeWithText("流派").assertIsDisplayed()
        compose.onNodeWithText("标签").assertDoesNotExist()
        compose.onNodeWithTag("genre-item:").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.media_genre_unknown)).assertIsDisplayed()
        compose.onNodeWithTag("genre-item:asmr").assertIsDisplayed()
        compose.onNodeWithTag("genre-item:slow jam/other").assertIsDisplayed()
        compose.onNodeWithTag("genre-item:slow jam").assertDoesNotExist()
        compose.onNodeWithTag("genre-item:other").assertDoesNotExist()
        compose.onNodeWithTag("genre-item:unrelated artist").assertDoesNotExist()
        compose.onNodeWithTag("genre-item:not indexed genre").assertDoesNotExist()
        captureIfRequested("genre-page.png")
        compose.onNodeWithTag("genre-search").performTextInput(" aSmR ")
        compose.onNodeWithTag("genre-item:asmr").assertIsDisplayed()
        compose.onNodeWithTag("genre-item:electronica").assertDoesNotExist()

        selectCategory(MediaCategory.ARTISTS)
        compose.onNodeWithTag("artist-search").performTextInput("unrelated")
        compose.onNodeWithTag("artist-item:unrelated artist").assertIsDisplayed()
        selectCategory(MediaCategory.GENRES)
        compose.onNodeWithTag("genre-item:asmr").assertIsDisplayed()
        compose.onNodeWithTag("genre-item:electronica").assertDoesNotExist()
        compose.onNodeWithTag("genre-search").performTextReplacement("absent genre")
        compose.onNodeWithText(compose.activity.getString(R.string.genre_browse_no_results)).assertIsDisplayed()
    }

    @Test fun genreSortDirectionAndAllCriteriaApplyOnlyAfterConfirmation() {
        val harness = showPage()
        selectCategory(MediaCategory.GENRES)
        assertBefore("asmr", "electronica")
        val original = harness.genreOptions.value
        compose.onNodeWithTag("genre-sort").performClick()
        captureIfRequested("genre-sort.png")
        compose.onNodeWithTag("genre-sort:DURATION").performClick()
        compose.onNodeWithTag("genre-sort:DURATION").performClick()
        compose.runOnIdle { assertEquals(original, harness.genreOptions.value) }
        compose.onNodeWithTag("genre-sort-cancel").performClick()
        assertBefore("asmr", "electronica")

        compose.onNodeWithTag("genre-sort").performClick()
        compose.onNodeWithTag("genre-sort:NAME").assertIsSelected().performClick()
        compose.onNodeWithTag("genre-sort-confirm").performClick()
        compose.runOnIdle { assertFalse(harness.genreOptions.value.ascending) }
        assertBefore("electronica", "asmr")
        listOf(SingerSortField.TRACK_COUNT, SingerSortField.DURATION, SingerSortField.UPDATED_AT).forEach { field ->
            val before = harness.genreOptions.value
            compose.onNodeWithTag("genre-sort").performClick()
            compose.onNodeWithTag("genre-sort:${field.name}").performClick()
            compose.runOnIdle { assertEquals(before, harness.genreOptions.value) }
            compose.onNodeWithTag("genre-sort-confirm").performClick()
            compose.runOnIdle {
                assertEquals(field, harness.genreOptions.value.sortField)
                assertFalse(harness.genreOptions.value.ascending)
                assertEquals(MediaSingerViewOptions(), harness.artistOptions.value)
            }
            assertBefore("asmr", "electronica")
        }
    }

    @Test fun genreSettingsKeepCompactRowsAndChangeOnlyGenrePresentation() {
        val harness = showPage()
        selectCategory(MediaCategory.GENRES)
        compose.onNodeWithTag("genre-search").performTextInput("ASMR")
        compose.onNodeWithTag("genre-settings").performClick()
        captureIfRequested("genre-settings.png")
        val infoBounds = setting("more-info").fetchSemanticsNode().boundsInRoot
        val groupBounds = compose.onNodeWithTag("genre-settings-group-tracks").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Settings rows must remain compact",
            groupBounds.top - infoBounds.bottom <= 8f * compose.activity.resources.displayMetrics.density)
        setting("more-info").performClick()
        compose.onNodeWithTag("genre-more-info:TRACK_COUNT").performClick()
        setting("group-tracks").performClick()
        setting("quick-search").performClick()
        compose.onNodeWithTag("genre-settings-back").performClick()
        compose.onNodeWithTag("genre-search").assertDoesNotExist()
        compose.onNodeWithTag("genre-item:electronica").assertIsDisplayed()
        compose.onNodeWithTag("genre-info:asmr", useUnmergedTree = true)
            .assertTextEquals(compose.activity.getString(R.string.media_album_detail_song_count, 3))
        compose.runOnIdle {
            assertFalse(harness.genreOptions.value.quickSearch)
            assertFalse(harness.genreOptions.value.groupTracks)
            assertEquals(SingerMoreInfo.TRACK_COUNT, harness.genreOptions.value.moreInfo)
            assertEquals(MediaSingerViewOptions(), harness.artistOptions.value)
        }
        selectCategory(MediaCategory.ARTISTS)
        compose.onNodeWithTag("artist-search").assertIsDisplayed()
        selectCategory(MediaCategory.GENRES)
        compose.onNodeWithTag("genre-search").assertDoesNotExist()
    }

    @Test fun genreAlbumFiltersAndTrackActionsUseTheDisplayedAudioVideoQueue() {
        val harness = showPage()
        selectCategory(MediaCategory.GENRES)
        compose.onNodeWithTag("genre-item:asmr").performClick()
        compose.onNodeWithTag("genre-album:alpha").assertIsSelected()
        val alpha = harness.paths(1, 4)
        compose.onNodeWithTag("genre-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(alpha, harness.shuffled.single()) }
        captureIfRequested("genre-detail.png")
        track(harness.path(4)).performClick()
        compose.runOnIdle { assertEquals(alpha to harness.path(4), harness.played.single()) }

        track(harness.path(1)).performTouchInput { longClick() }
        selectionAction(R.string.player_action_favorite)
        compose.runOnIdle { assertEquals(listOf(harness.path(1)), harness.favorited.single()) }
        track(harness.path(4)).performTouchInput { longClick() }
        selectionAction(R.string.player_action_add_to_playlist)
        compose.runOnIdle { assertEquals(listOf(harness.path(4)), harness.playlisted.single()) }
        track(harness.path(1)).performTouchInput { longClick() }
        selectionAction(R.string.media_lib_files_info)
        compose.runOnIdle { assertEquals(listOf(harness.path(1)), harness.information) }
        pressBack()
        compose.onNodeWithTag("genre-detail").assertIsDisplayed()

        compose.onNodeWithTag("genre-albums").performScrollToNode(hasTestTag("genre-album:beta"))
        compose.onNodeWithTag("genre-album:beta").performClick()
        compose.onNodeWithTag("genre-tracks-sort").performClick()
        compose.onNodeWithTag("album-track-sort-list").performScrollToNode(hasTestTag("album-track-sort:DURATION"))
        compose.onNodeWithTag("album-track-sort:DURATION").performClick()
        compose.onNodeWithTag("album-track-sort:DURATION").performClick()
        compose.runOnIdle { assertEquals(AlbumTrackSortField.TRACK_NUMBER, harness.genreOptions.value.trackSort.field) }
        compose.onNodeWithTag("album-track-sort-confirm").performClick()
        compose.onNodeWithTag("genre-tracks-shuffle").performClick()
        compose.runOnIdle { assertEquals(harness.paths(4, 3, 1), harness.shuffled.last()) }
    }

    @Test fun refreshingGenreTagsClearsMissingDetailsAndEmptyCatalogHasNoInventedGroup() {
        val harness = showPage()
        selectCategory(MediaCategory.GENRES)
        compose.onNodeWithTag("genre-item:asmr").performClick()
        compose.onNodeWithTag("genre-detail").assertIsDisplayed()
        compose.runOnIdle {
            harness.albums.value = buildMediaAlbums(harness.tracks.map {
                if (it.genre == "ASMR") it.copy(genre = "Renamed") else it
            })
        }
        compose.onNodeWithTag("genre-list").assertIsDisplayed()
        compose.onNodeWithTag("genre-item:renamed").assertIsDisplayed()
        compose.onNodeWithTag("genre-detail").assertDoesNotExist()
        selectCategory(MediaCategory.ARTISTS)
        compose.onNodeWithTag("artist-list").assertIsDisplayed()
        selectCategory(MediaCategory.GENRES)
        compose.runOnIdle { harness.albums.value = emptyList() }
        compose.onNodeWithText(compose.activity.getString(R.string.genre_browse_empty)).assertIsDisplayed()
        compose.onNodeWithTag("genre-item:").assertDoesNotExist()
    }

    private fun selectCategory(category: MediaCategory) {
        val tag = "media-category:${category.name}"
        compose.onNodeWithTag("media-category-tabs").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun setting(name: String) = compose.onNodeWithTag("genre-settings-list")
        .performScrollToNode(hasTestTag("genre-settings-$name")).let { compose.onNodeWithTag("genre-settings-$name") }

    private fun track(path: String) = compose.onNodeWithTag("genre-tracks")
        .performScrollToNode(hasTestTag("genre-track:$path")).let { compose.onNodeWithTag("genre-track:$path") }

    private fun selectionAction(label: Int) {
        compose.onNodeWithTag("library-selection-options").performClick()
        compose.onNodeWithText(compose.activity.getString(label)).performClick()
    }

    private fun assertBefore(first: String, second: String) {
        val firstTop = compose.onNodeWithTag("genre-item:$first").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithTag("genre-item:$second").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue("$first should appear before $second", firstTop < secondTop)
    }

    private fun captureIfRequested(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("genreScreenshot") != "true") return
        compose.waitForIdle()
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { screenshot.recycle() }
    }

    private fun showPage(): Harness {
        val root = File(compose.activity.cacheDir, "genre-page-test-${UUID.randomUUID()}").apply { mkdirs() }
        fixtureDirectory = root
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("media-tags/preservation.m4a").use { it.readBytes() }
        val genres = listOf("ASMR", "Electronica", "ASMR", "ASMR", "", "Slow Jam/Other")
        val tracks = (1..6).map { index ->
            val file = File(root, "Track $index.${if (index == 4) "mp4" else "m4a"}").apply { writeBytes(fixture) }
            MediaAlbumTrack(
                track = MediaLibraryTrack(file.path, "Track $index", artist = "Singer",
                    album = when (index) { 1, 4 -> "Alpha"; 2, 3 -> "Beta"; else -> "Delta" },
                    isVideo = index == 4, durationMs = index * 60_000L),
                albumArtist = "Unrelated artist", composer = "Unrelated composer", genre = genres[index - 1],
                trackNumber = index, modifiedAtMs = index * 1_000L
            )
        }
        File(root, "Not indexed genre.m4a").writeBytes(fixture)
        val harness = Harness(tracks)
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaAlbumPageContent(
                        albums = harness.albums.value,
                        options = MediaAlbumViewOptions(), onOptionsChanged = {},
                        artistOptions = harness.artistOptions.value,
                        onArtistOptionsChanged = { harness.artistOptions.value = it },
                        genreOptions = harness.genreOptions.value,
                        onGenreOptionsChanged = { harness.genreOptions.value = it },
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

    private class Harness(val tracks: List<MediaAlbumTrack>) {
        val albums = mutableStateOf(buildMediaAlbums(tracks))
        val artistOptions = mutableStateOf(MediaSingerViewOptions())
        val genreOptions = mutableStateOf(MediaSingerViewOptions())
        val played = mutableListOf<Pair<List<String>, String?>>()
        val shuffled = mutableListOf<List<String>>()
        val favorited = mutableListOf<List<String>>()
        val playlisted = mutableListOf<List<String>>()
        val information = mutableListOf<String>()
        fun path(index: Int) = tracks[index - 1].track.path
        fun paths(vararg indices: Int) = indices.map(::path)
    }
}
