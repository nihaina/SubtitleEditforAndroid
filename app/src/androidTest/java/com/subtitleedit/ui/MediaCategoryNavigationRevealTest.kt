package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.buildMediaAlbums
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Information links use path membership, including empty tags and songs outside scan roots. */
@RunWith(AndroidJUnit4::class)
class MediaCategoryNavigationRevealTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root by lazy { File(compose.activity.cacheDir, "category-reveal-${System.nanoTime()}").apply { mkdirs() } }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun eachCategoryOpensTheSongsOwnUnknownGroupWithoutGuessingFromDisplayLabels() {
        val target = albumTrack("target", "", "", "", "", "")
        val other = albumTrack("other", "Another album", "Another singer", "Another artist", "Another composer", "Another genre")
        val request = mutableStateOf<MediaCategoryRevealRequest?>(null)
        val albums = buildMediaAlbums(listOf(other, target))
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    MediaAlbumPageContent(albums, MediaAlbumViewOptions(), onOptionsChanged = {}, onPlay = { _, _ -> },
                        revealRequest = request.value, onRevealFinished = { token ->
                            if (request.value?.token == token) request.value = null
                        })
                }
            }
        }
        val categories = listOf(MediaCategory.ALBUMS to "album", MediaCategory.SINGERS to "singer",
            MediaCategory.ARTISTS to "artist", MediaCategory.COMPOSERS to "composer", MediaCategory.GENRES to "genre")
        categories.forEach { (category, tag) ->
            compose.runOnIdle { request.value = MediaCategoryRevealRequest(category, target.track.path) }
            compose.onNodeWithTag("$tag-detail").assertIsDisplayed()
            compose.onNodeWithTag("$tag-track:${target.track.path}").assertIsDisplayed()
            pressBack()
            compose.onNodeWithTag("$tag-detail").assertDoesNotExist()
            compose.runOnIdle { assertNull("Back consumes the pending reveal instead of reopening it", request.value) }
        }
    }

    @Test fun revealClearsTheRelevantSearchAndBackDoesNotReopenAfterCatalogRefresh() {
        val target = albumTrack("target", "Album", "Singer", "Artist", "Composer", "ASMR")
        val request = mutableStateOf<MediaCategoryRevealRequest?>(null)
        val albums = mutableStateOf(buildMediaAlbums(listOf(target)))
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaAlbumPageContent(albums.value, MediaAlbumViewOptions(), onOptionsChanged = {}, onPlay = { _, _ -> },
                    revealRequest = request.value, onRevealFinished = { token ->
                        if (request.value?.token == token) request.value = null
                    })
            }
        }
        compose.onNodeWithTag("media-category:GENRES").performClick()
        compose.onNodeWithTag("genre-search").performTextInput("does not match")
        compose.onNodeWithTag("genre-item:asmr").assertDoesNotExist()
        compose.runOnIdle { request.value = MediaCategoryRevealRequest(MediaCategory.GENRES, target.track.path) }
        compose.onNodeWithTag("genre-detail").assertIsDisplayed()
        compose.onNodeWithTag("genre-track:${target.track.path}").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("genre-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("genre-item:asmr").assertIsDisplayed()
        compose.runOnIdle {
            albums.value = buildMediaAlbums(listOf(target, albumTrack("new", "New", "New", "New", "New", "New")))
        }
        compose.onNodeWithTag("genre-detail").assertDoesNotExist()
        compose.runOnIdle { request.value = MediaCategoryRevealRequest(MediaCategory.GENRES, target.track.path) }
        compose.onNodeWithTag("genre-detail").assertIsDisplayed()
    }

    @Test fun anOutsideRootSongIsTemporaryAndNeverChangesTheScannedCatalogOrDirectories() {
        val directory = File(root, "scanned").apply { mkdirs() }
        val scanned = albumTrack("scanned/indexed", "Indexed", "Singer", "Artist", "Composer", "Genre")
        val outside = wav("outside/target.wav")
        val state = MediaAlbumPageState().apply {
            revealRequest = MediaCategoryRevealRequest(MediaCategory.GENRES, outside.path)
        }
        val loadedRoots = mutableListOf<List<String>>()
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaAlbumScreen(refreshVersion = 0, favoriteDirectories = listOf(directory), isActive = true,
                    pageState = state, catalogLoader = { roots -> loadedRoots += roots; listOf(scanned) })
            }
        }
        compose.waitUntil(12_000L) { state.revealTrack?.track?.path == outside.path && state.prepared != null }
        compose.onNodeWithTag("genre-detail").assertIsDisplayed()
        compose.onNodeWithTag("genre-track:${outside.path}").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(scanned), state.catalog?.tracks)
            assertEquals(listOf(scanned), state.prepared?.catalog?.tracks)
            assertTrue(loadedRoots.isNotEmpty())
            assertTrue(loadedRoots.all { it == listOf(directory.path + "/") })
            assertTrue(state.prepared?.albums?.flatMap { it.tracks }?.none { it.track.path == outside.path } == true)
        }
    }

    @Test fun currentTargetTagsReplaceTheSamePathsStaleCatalogGroupOnlyForNavigation() {
        val target = wav("edited/target.wav")
        val stale = MediaAlbumTrack(MediaLibraryTrack(path = target.path, title = "Stale title",
            artist = "Stale singer", album = "Stale album"), genre = "Stale genre")
        val state = MediaAlbumPageState().apply {
            revealRequest = MediaCategoryRevealRequest(MediaCategory.GENRES, target.path)
        }
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaAlbumScreen(refreshVersion = 0, favoriteDirectories = listOf(root), isActive = true,
                    pageState = state, catalogLoader = { listOf(stale) })
            }
        }
        compose.waitUntil(12_000L) { state.revealTrack != null && state.prepared != null }
        compose.onNodeWithTag("genre-detail").assertIsDisplayed()
        compose.onNodeWithTag("genre-track:${target.path}").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(com.subtitleedit.R.string.media_genre_unknown)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("The original scan snapshot is kept intact", listOf(stale), state.catalog?.tracks)
            assertEquals("The current file has no genre tag", "", state.revealTrack?.genre)
        }
    }

    @Test fun missingDirectTargetIsConsumedWithoutMutatingTheCatalog() {
        val scanned = albumTrack("existing", "Existing", "Singer", "Artist", "Composer", "Genre")
        val state = MediaAlbumPageState().apply {
            revealRequest = MediaCategoryRevealRequest(MediaCategory.ALBUMS, File(root, "missing.wav").path)
        }
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaAlbumScreen(0, emptyList(), true, state, catalogLoader = { listOf(scanned) })
            }
        }
        compose.waitUntil(12_000L) { state.revealRequest == null && state.prepared != null }
        compose.onNodeWithTag("album-detail").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(scanned), state.catalog?.tracks); assertNull(state.revealTrack) }
    }

    private fun albumTrack(name: String, album: String, singer: String, artist: String, composer: String, genre: String) =
        MediaAlbumTrack(MediaLibraryTrack(wav("$name.wav").path, name, artist = singer, album = album),
            albumArtist = artist, composer = composer, genre = genre)

    private fun wav(relative: String) = File(root, relative).apply {
        parentFile!!.mkdirs()
        val count = 1600
        writeBytes(ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
        }.array())
    }
}
