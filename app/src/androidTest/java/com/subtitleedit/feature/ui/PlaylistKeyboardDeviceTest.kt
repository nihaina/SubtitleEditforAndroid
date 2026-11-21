package com.subtitleedit.feature.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import com.subtitleedit.library.MediaPlaylist
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.player.PlayerPlaylistPickerDialog
import com.subtitleedit.player.PlayerUiState
import com.subtitleedit.ui.LibraryPlaylistAssignDialog
import com.subtitleedit.ui.MediaLibraryScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaylistKeyboardDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun mediaLibraryCanCreateAPlaylistWithTheKeyboardOpen() = checkLandscapeKeyboard { automation ->
        val repository = MediaLibraryRepository.getInstance(compose.activity)
        val name = "Keyboard create ${UUID.randomUUID()}"
        try {
            showMediaLibrary(repository)
            compose.onNodeWithContentDescription(label(R.string.media_lib_create_playlist)).performClick()
            typeNameAndConfirm(automation, name, R.string.confirm)
            compose.onNodeWithText(name).assertIsDisplayed()
        } finally {
            repository.getPlaylists().firstOrNull { it.name == name }?.let { repository.deletePlaylist(it.id) }
        }
    }

    @Test fun mediaLibraryCanRenameAPlaylistWithTheKeyboardOpen() = checkLandscapeKeyboard { automation ->
        val repository = MediaLibraryRepository.getInstance(compose.activity)
        val id = checkNotNull(repository.createPlaylist("Keyboard original ${UUID.randomUUID()}"))
        val name = "Keyboard renamed ${UUID.randomUUID()}"
        try {
            showMediaLibrary(repository)
            compose.onNodeWithTag("library-playlist-actions:$id").performClick()
            compose.onNodeWithText(label(R.string.media_lib_rename_playlist)).performClick()
            typeNameAndConfirm(automation, name, R.string.confirm)
            compose.onNodeWithText(name).assertIsDisplayed()
        } finally {
            repository.deletePlaylist(id)
        }
    }

    @Test fun libraryAssignmentCanCreateAPlaylistWithTheKeyboardOpen() =
        checkCreation(R.string.media_lib_create_playlist) { onCreate ->
            LibraryPlaylistAssignDialog(
                playlists = (1L..40L).map { MediaPlaylist(it, "Playlist $it") },
                onDismiss = {},
                onSelectPlaylist = {},
                onCreatePlaylist = onCreate,
            )
        }

    @Test fun playerCanCreateAPlaylistWithTheKeyboardOpen() =
        checkCreation(R.string.player_playlist_picker_new) { onCreate ->
            PlayerPlaylistPickerDialog(
                state = PlayerUiState(showPlaylistPickerDialog = true),
                onDismiss = {},
                onCreatePlaylist = { name, finish -> onCreate(name); finish(1L) },
                onConfirmAdd = {},
            )
        }

    private fun checkCreation(openLabel: Int, content: @Composable ((String) -> Unit) -> Unit) {
        checkLandscapeKeyboard { automation ->
            var created: String? = null
            compose.setContent { SubtitleEditComposeTheme { content { created = it } } }
            compose.onNodeWithText(label(openLabel)).performClick()
            typeNameAndConfirm(automation, "Keyboard playlist", R.string.player_playlist_picker_create)
            compose.runOnIdle { assertEquals("Keyboard playlist", created) }
        }
    }

    private fun showMediaLibrary(repository: MediaLibraryRepository) {
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaLibraryScreen(0, 0, { _, _, _ -> }, libraryRepository = repository)
            }
        }
        compose.onNodeWithText(label(R.string.media_lib_tab_playlists)).performClick()
    }

    private fun typeNameAndConfirm(automation: UiAutomation, name: String, confirmLabel: Int) {
        compose.onNode(hasSetTextAction()).performClick().performTextReplacement(name)
        compose.waitUntil(5_000L) { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        compose.onNodeWithText(label(confirmLabel)).assertIsDisplayed().performClick()
    }

    private fun label(resource: Int): String = compose.activity.getString(resource)

    private fun checkLandscapeKeyboard(check: (android.app.UiAutomation) -> Unit) {
        assumeTrue(
            "Pass playlistImeSmoke=true on a device with a software keyboard",
            InstrumentationRegistry.getArguments().getString("playlistImeSmoke") == "true",
        )
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val previous = compose.activity.requestedOrientation
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(5_000L) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            check(automation)
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            compose.activityRule.scenario.onActivity { it.requestedOrientation = previous }
        }
    }
}
