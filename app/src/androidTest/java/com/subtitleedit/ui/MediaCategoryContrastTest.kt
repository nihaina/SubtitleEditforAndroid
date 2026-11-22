package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.MediaAlbum
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaCategoryContrastTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun darkCategoryPageDoesNotInheritBlackTextAndIconsFromItsHost() {
        val track = MediaAlbumTrack(
            track = MediaLibraryTrack("/missing/category-test.mp3", "Sample track", "Sample singer", "Sample album"),
            genre = "Sample genre",
        )
        compose.setContent {
            SubtitleEditComposeTheme(darkTheme = true) {
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    MediaAlbumPageContent(
                        albums = listOf(MediaAlbum("Sample album", listOf(track))),
                        options = MediaAlbumViewOptions(),
                        onOptionsChanged = {},
                        onPlay = { _, _ -> },
                    )
                }
            }
        }
        assertBrightForeground(compose.onNodeWithTag("album-sort"))
        compose.onNodeWithTag("album-settings").performClick()
        assertBrightForeground(compose.onNodeWithTag("album-settings-view-mode"))
        compose.onNodeWithTag("album-settings-back").performClick()

        compose.onNodeWithTag("media-category:${MediaCategory.SINGERS.name}").performScrollTo().performClick()
        val singerName = compose.onNodeWithText("Sample singer", useUnmergedTree = true)
        assertBrightForeground(singerName)
        assertBrightForeground(compose.onNodeWithTag("singer-settings"))
        singerName.performClick()
        assertBrightForeground(compose.onNodeWithTag("singer-detail-back"))
    }

    private fun assertBrightForeground(node: SemanticsNodeInteraction) {
        val pixels = node.captureToImage().toPixelMap()
        var brightPixels = 0
        for (row in 0 until pixels.height) {
            for (column in 0 until pixels.width) {
                if (pixels[column, row].luminance() > 0.45f) brightPixels++
            }
        }
        assertTrue("Expected readable foreground pixels against the dark category background", brightPixels >= 8)
    }
}
