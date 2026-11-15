package com.subtitleedit.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PlayerLyricsContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun playbackCentersCurrentContextAndChoosingALineSeeksToItsTimestamp() {
        val lines = (0..29).map { PlayerLyricLine(it * 10_000L, (it + 1) * 10_000L, "Lyric $it") }
        val position = mutableStateOf(1_000L)
        var sought: Long? = null
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 400.dp)) {
                    PlayerLyricsView(lines, position.value, "", false, 20, { sought = it }, {})
                }
            }
        }
        compose.onNodeWithTag("player-lyrics-line:0").assertIsDisplayed().assertIsSelected()
        compose.runOnIdle { position.value = 151_000L }
        val active = compose.onNodeWithTag("player-lyrics-line:15").assertIsDisplayed().assertIsSelected()
            .fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("player-lyrics-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Current lyric should stay near the center of its own viewport", abs(active.center.y - list.center.y) <= active.height)
        compose.onNodeWithTag("player-lyrics-line:14").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(140_000L, sought) }
    }

    @Test fun emptyLyricsOfferSubtitleSelectionAndNativeTextCanStillBeRead() {
        val text = mutableStateOf("")
        var selections = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 400.dp)) {
                    PlayerLyricsView(emptyList(), 0L, text.value, false, 20, {}, { selections++ })
                }
            }
        }
        compose.onNodeWithTag("player-lyrics-select-subtitle").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, selections)
            text.value = "Native stream subtitle"
        }
        compose.onNodeWithText("Native stream subtitle").assertIsDisplayed()
        compose.onNodeWithTag("player-lyrics-select-subtitle").assertDoesNotExist()
    }

    @Test fun lyricRowsPreserveTheParsedTextAndEmptyEntries() {
        val lines = listOf(
            PlayerLyricLine(0L, 10_000L, "  <i>First</i>\n\nSecond &amp; third  "),
            PlayerLyricLine(10_000L, 20_000L, ""),
            PlayerLyricLine(20_000L, 30_000L, "  Final  ")
        )
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 400.dp)) {
                    PlayerLyricsView(lines, 1_000L, "", false, 20, {}, {})
                }
            }
        }
        lines.forEachIndexed { index, line ->
            val tag = "player-lyrics-line:$index"
            compose.onNodeWithTag("player-lyrics-list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed().assertTextEquals(line.text)
        }
    }
}
