package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaPlaylist
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoritesPlaylistUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val favoritesId get() = MediaLibraryRepository.FAVORITES_PLAYLIST_ID
    private val favoritesName get() = compose.activity.getString(R.string.media_lib_tab_favorites)
    private fun playlists() = listOf(
        MediaPlaylist(favoritesId, favoritesName, isSystem = true),
        MediaPlaylist(7L, favoritesName),
    )

    @Test fun defaultPlaylistIsOpenableWhileOnlyUserPlaylistsHaveManagementActions() {
        var opened: Long? = null
        var renamed: Long? = null
        var deleted: Long? = null
        val playlists = playlists()
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 600.dp)) {
                    CustomPlaylistsTabContent(
                        playlists,
                        onOpen = { opened = it.id },
                        onRename = { renamed = it.id },
                        onDelete = { deleted = it.id },
                    )
                }
            }
        }

        compose.onNodeWithText(compose.activity.getString(R.string.media_lib_system_playlist_count, 0)).assertIsDisplayed()
        compose.onNodeWithTag("library-playlist-actions:$favoritesId").assertDoesNotExist()
        compose.onNodeWithTag("library-playlist:$favoritesId").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(favoritesId, opened) }
        compose.onNodeWithTag("library-playlist-actions:7").assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.media_lib_rename_playlist)).performClick()
        compose.runOnIdle { assertEquals(7L, renamed) }
        compose.onNodeWithTag("library-playlist-actions:7").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.delete)).performClick()
        compose.runOnIdle { assertEquals(7L, deleted) }
    }

    @Test fun addToPlaylistDialogAllowsSelectingTheDefaultFavoritesPlaylist() {
        var selected: Long? = null
        val playlists = playlists()
        compose.setContent {
            SubtitleEditComposeTheme {
                LibraryPlaylistAssignDialog(
                    playlists,
                    onDismiss = {},
                    onSelectPlaylist = { selected = it.id },
                    onCreatePlaylist = { error("Selecting favorites must not create another playlist") },
                )
            }
        }

        compose.onNodeWithText(compose.activity.getString(R.string.media_lib_system_playlist)).assertIsDisplayed()
        compose.onNodeWithTag("library-playlist-choice:$favoritesId").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(favoritesId, selected) }
    }
}
