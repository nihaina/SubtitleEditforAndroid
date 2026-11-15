package com.subtitleedit.player

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Verifies actual text layout and the audio-only subtitle controls without starting native playback. */
@RunWith(AndroidJUnit4::class)
class PlayerAudioSubtitleLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun alignmentMovesGlyphsWithoutChangingTheSubtitleOrItsFullWidthRow() {
        val alignment = mutableStateOf(PlayerSubtitleAlignment.CENTER)
        val text = "Short lyric"
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 400.dp)) {
                    PlayerLyricsView(listOf(PlayerLyricLine(0L, 10_000L, text)), 1_000L,
                        "", false, 20, {}, {}, alignment = alignment.value)
                }
            }
        }
        fun layout(): TextLayoutResult {
            val result = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("player-lyrics-line:0").assertIsDisplayed().assertTextEquals(text)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
            return result.single()
        }
        val centered = layout()
        val rowWidth = compose.onNodeWithTag("player-lyrics-line:0").fetchSemanticsNode().boundsInRoot.width
        assertEquals("Centered text has equal left and right free space", centered.getLineLeft(0),
            centered.size.width - centered.getLineRight(0), 1.5f)

        compose.runOnIdle { alignment.value = PlayerSubtitleAlignment.LEFT }
        val left = layout()
        assertEquals(0f, left.getLineLeft(0), 1.5f)
        compose.runOnIdle { alignment.value = PlayerSubtitleAlignment.RIGHT }
        val right = layout()
        assertEquals(right.size.width.toFloat(), right.getLineRight(0), 1.5f)
        assertTrue("The three alignments must occupy different horizontal positions",
            left.getLineLeft(0) < centered.getLineLeft(0) && centered.getLineLeft(0) < right.getLineLeft(0))
        assertEquals("Alignment must keep the highlight row full width", rowWidth,
            compose.onNodeWithTag("player-lyrics-line:0").fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun spacingChangesRowAndMultilineLayoutWhilePreservingBlankLines() {
        val spacing = mutableStateOf(0)
        val text = "First line\n\nThird line"
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 440.dp)) {
                    PlayerLyricsView(listOf(PlayerLyricLine(0L, 10_000L, text)), 1_000L,
                        "", false, 20, {}, {}, lineSpacing = spacing.value)
                }
            }
        }
        fun layout(): TextLayoutResult {
            val result = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("player-lyrics-line:0").assertIsDisplayed().assertTextEquals(text)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
            return result.single()
        }
        val compact = layout()
        val compactHeight = compose.onNodeWithTag("player-lyrics-line:0").fetchSemanticsNode().boundsInRoot.height
        assertEquals(3, compact.lineCount)
        compose.runOnIdle { spacing.value = 48 }
        val expanded = layout()
        val expandedHeight = compose.onNodeWithTag("player-lyrics-line:0").fetchSemanticsNode().boundsInRoot.height
        assertEquals(3, expanded.lineCount)
        assertTrue("Line spacing must enlarge spacing within multiline cues",
            expanded.getLineBaseline(2) - expanded.getLineBaseline(0) >
                compact.getLineBaseline(2) - compact.getLineBaseline(0))
        assertTrue("Line spacing must also enlarge the row interval", expandedHeight > compactHeight)
        val row = compose.onNodeWithTag("player-lyrics-line:0").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("player-lyrics-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Changing spacing while paused must recenter the newly measured current cue",
            abs(row.center.y - list.center.y) <= 4f)
    }

    @Test fun preferencesRestoreLayoutChoicesAndClampSpacingWithoutChangingVideoFontSize() {
        val name = "player-audio-subtitle-layout-${System.nanoTime()}"
        val raw = compose.activity.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val preferences = PlayerPreferences(raw)
            assertEquals(24, preferences.audioSubtitleLineSpacing)
            assertEquals(PlayerSubtitleAlignment.CENTER, preferences.audioSubtitleAlignment)
            preferences.subtitleFontSize = 31
            preferences.audioSubtitleLineSpacing = 35
            preferences.audioSubtitleAlignment = PlayerSubtitleAlignment.RIGHT
            val restored = PlayerPreferences(raw)
            assertEquals(35, restored.audioSubtitleLineSpacing)
            assertEquals(PlayerSubtitleAlignment.RIGHT, restored.audioSubtitleAlignment)
            assertEquals(31, restored.subtitleFontSize)
            restored.audioSubtitleLineSpacing = -10
            assertEquals(0, PlayerPreferences(raw).audioSubtitleLineSpacing)
            restored.audioSubtitleLineSpacing = 100
            assertEquals(48, PlayerPreferences(raw).audioSubtitleLineSpacing)
            assertTrue(raw.edit().putInt("audio_subtitle_line_spacing", -99)
                .putString("audio_subtitle_alignment", "invalid").commit())
            assertEquals(0, PlayerPreferences(raw).audioSubtitleLineSpacing)
            assertEquals(PlayerSubtitleAlignment.CENTER, PlayerPreferences(raw).audioSubtitleAlignment)
        } finally { compose.activity.deleteSharedPreferences(name) }
    }

    @Test fun subtitlePanelAppliesAudioLayoutImmediatelyAndHidesItForVideo() {
        val preferences = compose.activity.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val keys = listOf("audio_subtitle_line_spacing", "audio_subtitle_alignment")
        val previous = keys.associateWith { preferences.all[it] }
        val video = mutableStateOf(false)
        lateinit var model: PlayerViewModel
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model = ViewModelProvider(compose.activity)[PlayerViewModel::class.java]
                model.setAudioSubtitleLineSpacing(24)
                model.setAudioSubtitleAlignment(PlayerSubtitleAlignment.CENTER)
            }
            compose.setContent {
                val state by model.state.collectAsState()
                SubtitleEditComposeTheme {
                    PlayerPanelSheet(state.copy(panel = PlayerPanel.SUBTITLE, isVideo = video.value), model)
                }
            }
            compose.onNodeWithTag("player-subtitle-align:CENTER").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("player-subtitle-align:LEFT").performScrollTo().performClick().assertIsSelected()
            compose.runOnIdle {
                assertEquals(PlayerSubtitleAlignment.LEFT, model.state.value.audioSubtitleAlignment)
                assertEquals(PlayerSubtitleAlignment.LEFT, PlayerPreferences(preferences).audioSubtitleAlignment)
            }
            compose.onNodeWithTag("player-subtitle-align:RIGHT").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("player-subtitle-line-spacing").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(36f) }
            compose.runOnIdle {
                assertEquals(36, model.state.value.audioSubtitleLineSpacing)
                assertEquals(36, PlayerPreferences(preferences).audioSubtitleLineSpacing)
                assertEquals(PlayerSubtitleAlignment.RIGHT, PlayerPreferences(preferences).audioSubtitleAlignment)
            }
            if (InstrumentationRegistry.getArguments().getString("playerScreenshot") == "true") {
                compose.waitForIdle()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.waitForIdle(300L, 3_000L)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                if (screenshot != null) try {
                    File(instrumentation.targetContext.getExternalFilesDir(null), "player-audio-subtitle-layout.png")
                        .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { screenshot.recycle() }
            }
            compose.runOnIdle { video.value = true }
            compose.onNodeWithTag("player-subtitle-line-spacing").assertDoesNotExist()
            PlayerSubtitleAlignment.entries.forEach {
                compose.onNodeWithTag("player-subtitle-align:${it.name}").assertDoesNotExist()
            }
        } finally {
            val editor = preferences.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                    else -> error("Unexpected test preference value")
                }
            }
            assertTrue(editor.commit())
        }
    }
}
