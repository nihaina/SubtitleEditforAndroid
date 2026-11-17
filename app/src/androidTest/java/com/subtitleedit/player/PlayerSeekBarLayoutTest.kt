package com.subtitleedit.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The rendered slider must not move when proportional time glyphs or hour counts change. */
@RunWith(AndroidJUnit4::class)
class PlayerSeekBarLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun elapsedGlyphsAndHourBoundariesDoNotResizeTheSlider() {
        val state = mutableStateOf(PlayerUiState(ready = true))
        show(state)
        listOf(59_000L, 5_400_000L, 450_000_000L).forEach { duration ->
            compose.runOnIdle { state.value = PlayerUiState(ready = true, durationMs = duration) }
            val baseline = bounds(SLIDER)
            listOf(1_000L, 8_000L, 59_000L, 60_000L, 671_000L, 3_599_000L,
                3_600_000L, 39_600_000L, 449_999_000L).filter { it <= duration }.forEach { position ->
                compose.runOnIdle { state.value = state.value.copy(positionMs = position) }
                compose.onNodeWithTag(CURRENT).assertTextEquals(formatPlaybackTime(position))
                assertStableSlider(baseline)
                assertTimeSlots()
            }
        }
    }

    @Test fun enlargedRtlTimeAndSeekPreviewsKeepEqualPhysicalSlots() {
        val state = mutableStateOf(PlayerUiState(ready = true, isVideo = true,
            durationMs = 450_000_000L, positionMs = 1_000L))
        show(state, fontScale = 1.6f, direction = LayoutDirection.Rtl)
        val baseline = bounds(SLIDER)
        listOf(8_000L, 671_000L, 3_599_000L, 3_600_000L, 39_600_000L, 449_999_000L).forEach { preview ->
            compose.runOnIdle { state.value = state.value.copy(seekPreviewMs = preview) }
            compose.onNodeWithTag(CURRENT).assertTextEquals(formatPlaybackTime(preview))
            assertStableSlider(baseline)
            assertTimeSlots()
        }
        compose.runOnIdle { state.value = state.value.copy(seekPreviewMs = null) }
        compose.onNodeWithTag(CURRENT).assertTextEquals("00:01")
        assertStableSlider(baseline)
        assertTimeSlots()
    }

    private fun show(state: MutableState<PlayerUiState>, fontScale: Float = 1f,
                     direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            SubtitleEditComposeTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides direction,
                    LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif)
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        PlayerSeekBar(state.value, onSeekPreview = {}, onSeekFinished = {})
                    }
                }
            }
        }
    }

    private fun assertStableSlider(expected: Rect) {
        val actual = bounds(SLIDER)
        assertEquals("Changing time text must not move the slider's left edge", expected.left, actual.left, 0.1f)
        assertEquals("Changing time text must not move the slider's right edge", expected.right, actual.right, 0.1f)
    }

    private fun assertTimeSlots() {
        val current = bounds(CURRENT)
        val total = bounds(TOTAL)
        val slider = bounds(SLIDER)
        assertEquals("Both time regions reserve the same width", current.width, total.width, 0.1f)
        assertTrue("Elapsed time remains physically left of the slider: current=$current slider=$slider total=$total", current.right < slider.left)
        assertTrue("Total time remains physically right of the slider: current=$current slider=$slider total=$total", total.left > slider.right)
        val currentLayout = textLayout(CURRENT)
        val totalLayout = textLayout(TOTAL)
        assertFalse("Elapsed time must fit its reserved region", currentLayout.hasVisualOverflow)
        assertFalse("Total time must fit its reserved region", totalLayout.hasVisualOverflow)
        assertEquals("Elapsed glyphs stay left aligned within their region", 0f, currentLayout.getLineLeft(0), 0.1f)
        assertEquals("Total glyphs stay right aligned within their region",
            totalLayout.size.width.toFloat(), totalLayout.getLineRight(0), 1f)
    }

    // Slider's accessibility bounds expand horizontally beyond its rendered layout.
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .layoutInfo.coordinates.boundsInRoot()

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private companion object {
        const val SLIDER = "player-seek"
        const val CURRENT = "player-current-time"
        const val TOTAL = "player-duration-time"
    }
}
