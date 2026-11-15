package com.subtitleedit.feature.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.MediaPlaylist
import com.subtitleedit.ui.LibraryPlaylistAssignDialog
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPlaylistAssignDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun portraitKeepsCreationControlsReachableWithManyPlaylists() =
        checkCreationControls(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)

    @Test fun landscapeKeepsCreationControlsReachableWithManyPlaylists() =
        checkCreationControls(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)

    private fun checkCreationControls(request: Int, expected: Int) {
        val previous = compose.activity.requestedOrientation
        compose.activityRule.scenario.onActivity { it.requestedOrientation = request }
        compose.waitUntil(5_000L) { compose.activity.resources.configuration.orientation == expected }
        var created: String? = null
        var selected = -1L
        try {
            compose.setContent {
                SubtitleEditComposeTheme {
                    LibraryPlaylistAssignDialog(
                        playlists = (1L..40L).map { MediaPlaylist(it, "歌单 $it") },
                        onDismiss = {},
                        onSelectPlaylist = { selected = it.id },
                        onCreatePlaylist = { created = it }
                    )
                }
            }
            compose.onNodeWithText("新建歌单").assertIsDisplayed()
            compose.onNodeWithTag("library-playlist-choices").performScrollToNode(hasText("歌单 40"))
            compose.onNodeWithText("歌单 40").assertIsDisplayed().performClick()
            assertEquals(40L, selected)
            compose.onNodeWithText("新建歌单").assertIsDisplayed().performClick()
            compose.onNodeWithText("输入歌单名称").assertIsDisplayed().performClick().performTextInput("New playlist")
            compose.onNodeWithText("创建").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("New playlist", created) }
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = previous }
        }
    }
}
