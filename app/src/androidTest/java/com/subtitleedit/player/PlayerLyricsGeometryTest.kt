package com.subtitleedit.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerLyricsGeometryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun zeroSpacingHasNoFixedRowHeightOrExtraGap() {
        val lines = (0..8).map { PlayerLyricLine(it * 10_000L, (it + 1) * 10_000L, "Line $it") }
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 400.dp)) {
                    PlayerLyricsView(lines, 21_000L, "", false, 20, {}, {}, lineSpacing = 0)
                }
            }
        }
        fun textBounds(index: Int) = compose.onNodeWithTag("player-lyrics-text:$index", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val before = textBounds(1)
        val current = textBounds(2)
        val after = textBounds(3)
        assertTrue("Zero spacing should not impose a 48dp row", current.height < with(compose.density) { 32.dp.toPx() })
        assertEquals("No extra gap above the current line", before.bottom, current.top, 1.5f)
        assertEquals("No extra gap below the current line", current.bottom, after.top, 1.5f)
    }

    @Test fun multilineTextAndFirstLastCueStayCenteredAcrossTypographyAndViewportChanges() {
        val spacing = mutableStateOf(24)
        val fontSize = mutableStateOf(20)
        val height = mutableStateOf(440.dp)
        val position = mutableStateOf(1_000L)
        val lines = listOf(
            PlayerLyricLine(0L, 10_000L, "First\n\nThird"),
            PlayerLyricLine(10_000L, 20_000L, "Middle"),
            PlayerLyricLine(20_000L, 30_000L, "Final\nLast")
        )
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, height.value)) {
                    PlayerLyricsView(lines, position.value, "", false, fontSize.value, {}, {}, lineSpacing = spacing.value)
                }
            }
        }
        fun assertCentered(index: Int) {
            val row = compose.onNodeWithTag("player-lyrics-line:$index").assertIsDisplayed().assertIsSelected()
                .fetchSemanticsNode().boundsInRoot
            val text = compose.onNodeWithTag("player-lyrics-text:$index", useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val list = compose.onNodeWithTag("player-lyrics-list").fetchSemanticsNode().boundsInRoot
            assertTrue("Current row should be centered in the actual viewport", abs(row.center.y - list.center.y) <= 3f)
            assertTrue("Text should be vertically centered inside its highlighted row", abs(text.center.y - row.center.y) <= 3f)
        }
        assertCentered(0)
        compose.runOnIdle { spacing.value = 0; fontSize.value = 32 }
        assertCentered(0)
        compose.runOnIdle { position.value = 21_000L }
        assertCentered(2)
        compose.runOnIdle { height.value = 300.dp; spacing.value = 30 }
        assertCentered(2)
    }

    @Test fun onlyUserScrollingReportsActivityRatherThanProgrammaticFollowing() {
        val calls = AtomicInteger()
        val position = mutableStateOf(1_000L)
        val lines = (0..49).map { PlayerLyricLine(it * 10_000L, (it + 1) * 10_000L, "Line $it") }
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 400.dp)) {
                    PlayerLyricsView(lines, position.value, "", false, 20, {}, {}, onUserScroll = { calls.incrementAndGet() })
                }
            }
        }
        compose.onNodeWithTag("player-lyrics-line:0").assertIsDisplayed()
        compose.runOnIdle { position.value = 201_000L }
        compose.onNodeWithTag("player-lyrics-line:20").assertIsDisplayed()
        assertEquals("Following the playback clock is not a user scroll", 0, calls.get())
        compose.onNodeWithTag("player-lyrics-list").performTouchInput { swipeUp(durationMillis = 300) }
        compose.runOnIdle { assertTrue("Real drag and fling report activity", calls.get() > 0) }
    }
}
