package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.AlbumTrackSortOptions
import com.subtitleedit.library.MediaAlbum
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaCreditRole
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.MediaSinger
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTrackRevealTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val roots = mutableListOf<File>()

    @After fun removeFixtures() { roots.forEach(File::deleteRecursively) }

    @Test fun farSongScrollsIntoViewAndReceivesExactlyThreeFinitePulses() {
        val tracks = tracks(48)
        val target = tracks.last().track.path
        val request = MediaCategoryRevealRequest(MediaCategory.ALBUMS, target, token = 101L)
        val finished = mutableListOf<Long>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 420.dp)) {
                    MediaAlbumDetailContent(
                        album = MediaAlbum("Reveal album", tracks), groupTracks = false,
                        sortField = AlbumSortField.NAME, ascending = true, trackSort = AlbumTrackSortOptions(),
                        onTrackSortChanged = {}, isActive = true, onBack = {}, onPlay = { _, _ -> },
                        onShuffle = {}, onFavoriteSelection = { _, done -> done() },
                        onAddSelectionToPlaylist = { _, done -> done() }, onShowInfo = {},
                        revealRequest = request, onRevealFinished = { finished += it }
                    )
                }
            }
        }
        val tag = "album-track:$target"
        for (pulse in 1..3) {
            advanceUntil { pulse(tag) == pulse && opacity(tag) >= 0.9f }
            compose.onNodeWithTag(tag).assertIsDisplayed()
            compose.runOnIdle { assertTrue("No request is consumed before the third pulse finishes", finished.isEmpty()) }
        }
        advanceUntil { finished == listOf(101L) }
        advanceUntil { pulse(tag) == 0 }
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(MediaTrackRevealPulse, 0))
        compose.mainClock.advanceTimeBy(5_000L)
        compose.runOnIdle { assertEquals(listOf(101L), finished) }
    }

    @Test fun albumRevealSelectsTheContainingSingerGroupWithoutChangingSort() {
        val tracks = tracks(4).mapIndexed { index, track ->
            track.copy(track = track.track.copy(artist = if (index < 2) "First singer" else "Target singer"))
        }
        val target = tracks.last().track.path
        var sortChanges = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 520.dp)) {
                    MediaAlbumDetailContent(
                        album = MediaAlbum("Grouped album", tracks), groupTracks = true,
                        sortField = AlbumSortField.NAME, ascending = true, trackSort = AlbumTrackSortOptions(),
                        onTrackSortChanged = { sortChanges++ }, isActive = true, onBack = {}, onPlay = { _, _ -> },
                        onShuffle = {}, onFavoriteSelection = { _, done -> done() },
                        onAddSelectionToPlaylist = { _, done -> done() }, onShowInfo = {},
                        revealRequest = MediaCategoryRevealRequest(MediaCategory.ALBUMS, target, 102L)
                    )
                }
            }
        }
        advanceUntil { pulse("album-track:$target") == 1 }
        compose.onNodeWithTag("album-track:$target").assertIsDisplayed()
        // The header may be scrolled out, but its state must not add the original group's rows.
        compose.onNodeWithTag("album-track:${tracks.first().track.path}").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, sortChanges) }
    }

    @Test fun creditRevealSelectsTheContainingAlbumAndCancelsWhenInactive() {
        val tracks = tracks(4).mapIndexed { index, track ->
            track.copy(track = track.track.copy(album = if (index < 2) "A first album" else "Z target album"))
        }
        val target = tracks.last().track.path
        val active = mutableStateOf(true)
        val finished = mutableListOf<Long>()
        var optionsChanges = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 520.dp)) {
                    MediaSingerDetailContent(
                        singer = MediaSinger("Genre", tracks), options = MediaSingerViewOptions(groupTracks = true),
                        onOptionsChanged = { optionsChanges++ }, isActive = active.value, onBack = {},
                        onPlay = { _, _ -> }, onShuffle = {}, onFavoriteSelection = { _, done -> done() },
                        onAddSelectionToPlaylist = { _, done -> done() }, onShowInfo = {}, role = MediaCreditRole.GENRE,
                        revealRequest = MediaCategoryRevealRequest(MediaCategory.GENRES, target, 103L),
                        onRevealFinished = { finished += it }
                    )
                }
            }
        }
        val tag = "genre-track:$target"
        advanceUntil { pulse(tag) == 1 && opacity(tag) >= 0.9f }
        compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.runOnIdle { active.value = false }
        compose.mainClock.advanceTimeBy(5_000L)
        compose.runOnIdle {
            assertTrue("A hidden page must not consume the locating request", finished.isEmpty())
            assertEquals(0, optionsChanges)
        }
    }

    private fun tracks(count: Int): List<MediaAlbumTrack> {
        val root = File(compose.activity.cacheDir, "category-reveal-${System.nanoTime()}").apply { check(mkdirs()) }
        roots += root
        return (0 until count).map { index ->
            val file = File(root, "song-${index.toString().padStart(3, '0')}.txt").apply { writeText("Song $index") }
            MediaAlbumTrack(MediaLibraryTrack(path = file.path, title = file.nameWithoutExtension,
                artist = "Singer", album = "Album"))
        }
    }

    private fun pulse(tag: String): Int? = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(MediaTrackRevealPulse)

    private fun opacity(tag: String): Float = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(MediaTrackRevealOpacity) ?: 0f

    private fun advanceUntil(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 8_000L) {
            compose.mainClock.advanceTimeByFrame()
            condition()
        }
    }
}
