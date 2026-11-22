package com.subtitleedit.feature.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.ui.settings.AsrTimelineSettingsScreen
import com.subtitleedit.ui.settings.AsrTimelineSettingsState
import com.subtitleedit.ui.settings.ModelSettingsGroup
import com.subtitleedit.ui.settings.ModelSettingsSliderTile
import com.subtitleedit.ui.settings.SettingsTileSwitchRow
import com.subtitleedit.ui.settings.WhisperSettingsScreen
import com.subtitleedit.ui.settings.WhisperSettingsState
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsrSettingsPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun middleSliderUsesInnerCornersAndTheGroupKeepsItsRoundedOutline() {
        compose.setContent {
            SubtitleEditComposeTheme {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    Box(Modifier.width(240.dp).background(Color.Magenta)) {
                        ModelSettingsGroup(title = "Settings") {
                            SettingsTileSwitchRow(
                                title = "Merge",
                                checked = false,
                                onCheckedChange = {},
                                modifier = Modifier.testTag("first-tile"),
                            )
                            Box(Modifier.testTag("middle-tile")) {
                                ModelSettingsSliderTile("Gap", 0.5f, {}, 0f..1f, steps = 0)
                            }
                            SettingsTileSwitchRow(title = "Smart merge", checked = false, onCheckedChange = {})
                        }
                    }
                }
            }
        }
        val first = compose.onNodeWithTag("first-tile").captureToImage().toPixelMap()
        val middle = compose.onNodeWithTag("middle-tile").captureToImage().toPixelMap()
        assertEquals(Color.Magenta.toArgb(), first[12, 6].toArgb())
        assertEquals(middle[middle.width / 2, 6].toArgb(), middle[12, 6].toArgb())
    }

    @Test fun timelineVadSwitchKeepsTheExistingNavigationAndAlignmentAvailability() {
        val state = mutableStateOf(AsrTimelineSettingsState(useVadTimestamp = true))
        var vadOpens = 0
        compose.setContent {
            SubtitleEditComposeTheme {
                AsrTimelineSettingsScreen(
                    title = "SenseVoice",
                    state = state.value,
                    onBack = {},
                    onOpenVadSettings = { vadOpens++ },
                    onUseVadTimestampChanged = { state.value = state.value.copy(useVadTimestamp = it) },
                    onDynamicPaddingEnabledChanged = {},
                    onFixedSegmentSecondsChanged = {},
                    onFixedSegmentSecondsTextChanged = {},
                    onFixedVadSegmentationChanged = {},
                    onTokenTimestampEnabledChanged = {},
                    onTokenTimestampMergeChanged = {},
                    onSmartMergeChanged = {},
                    onFilterLongMergeChanged = {},
                    onPunctuationSplitChanged = {},
                    onSplitPunctuationChanged = {},
                    onMergeMaxCharactersChanged = {},
                    onMergeGapChanged = {},
                    onMergeGapTextChanged = {},
                )
            }
        }
        val vadLink = compose.onNodeWithText(label(R.string.asr_vad_settings_entry))
        val vadSwitch = compose.onNodeWithText(label(R.string.qwen3_asr_vad_switch))
        val alignment = compose.onNodeWithText(label(R.string.activity_speech_to_subtitle_settings_text_42))
        vadLink.performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, vadOpens) }
        alignment.assertDoesNotExist()
        vadSwitch.performScrollTo().assertIsOn().performClick()
        vadSwitch.assertIsOff()
        vadLink.assertDoesNotExist()
        alignment.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.activity_speech_to_subtitle_settings_text_47))
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun whisperHotwordsRemainEditableAndRequireExplicitSave() {
        val state = mutableStateOf(WhisperSettingsState())
        var saved: String? = null
        compose.setContent {
            SubtitleEditComposeTheme {
                WhisperSettingsScreen(
                    state = state.value,
                    onBack = {},
                    onOpenVadSettings = {},
                    onDynamicPaddingEnabledChanged = {},
                    onThreadsChanged = {},
                    onHotwordsEnabledChanged = { state.value = state.value.copy(hotwordsEnabled = it) },
                    onHotwordsChanged = { state.value = state.value.copy(hotwords = it) },
                    onSaveHotwords = { saved = state.value.hotwords },
                    onHotwordsScoreChanged = {},
                )
            }
        }
        compose.onNodeWithText(label(R.string.activity_whisper_settings_hotwords_title))
            .performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Whisper")
        compose.runOnIdle { assertEquals(null, saved) }
        compose.onNodeWithText(label(R.string.activity_whisper_settings_hotwords_save)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Whisper", saved) }
    }

    private fun label(id: Int): String = compose.activity.getString(id)
}
