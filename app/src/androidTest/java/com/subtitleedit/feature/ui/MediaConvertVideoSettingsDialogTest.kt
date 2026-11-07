package com.subtitleedit.feature.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaConvertBitrateMode
import com.subtitleedit.MediaConvertDeinterlace
import com.subtitleedit.MediaConvertRotation
import com.subtitleedit.MediaConvertVideoSettings
import com.subtitleedit.MediaConvertVideoFilter
import com.subtitleedit.util.ByteSizeFormat
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaConvertVideoSettingsDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rotationAndDeinterlaceUseDropdownsAndApplyImmediately() {
        val values = mutableListOf<MediaConvertVideoSettings>()
        val settings = mutableStateOf(MediaConvertVideoSettings())
        compose.setContent { SubtitleEditComposeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MediaConvertVideoMoreSettings(settings.value, "libx264", MediaConvertBitrateMode.AUTO,
                    null, null, false, false, { settings.value = it; values += it })
            }
        } }
        fun select(setting: String, label: String) {
            compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("video-more-$setting"))).performScrollTo().performClick()
            compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
            compose.onNodeWithTag("video-setting-confirm").assertDoesNotExist()
        }
        for ((rotation, label) in listOf(MediaConvertRotation.CLOCKWISE to "顺时针 90°", MediaConvertRotation.HALF to "旋转 180°",
            MediaConvertRotation.COUNTERCLOCKWISE to "逆时针 90°", MediaConvertRotation.NONE to "关闭")) {
            select("ROTATION", label)
            assertEquals(rotation, values.last().rotation)
        }
        for ((mode, label) in listOf(MediaConvertDeinterlace.AUTO to "自动（仅交错帧）", MediaConvertDeinterlace.ON to "开启（所有帧）",
            MediaConvertDeinterlace.OFF to "关闭")) {
            select("DEINTERLACE", label)
            assertEquals(mode, values.last().deinterlace)
        }
    }

    @Test fun cancelDiscardsPixelChoiceAndOnlySupportedFormatsAreListed() {
        var dismissed = false
        val values = mutableListOf<MediaConvertVideoSettings>()
        compose.setContent { SubtitleEditComposeTheme {
            MediaConvertVideoSettingDialog(MediaConvertVideoSetting.PIXEL_FORMAT, MediaConvertVideoSettings(), "libx265",
                listOf("yuv420p10le"), "yuv420p", false, { dismissed = true }, { values += it })
        } }
        compose.onNodeWithTag("video-setting-option-yuv420p10le").performClick()
        compose.onNodeWithTag("video-setting-option-nv12").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        assertTrue(dismissed)
        assertTrue(values.isEmpty())
    }

    @Test fun crfValidationRejectsValuesOutsideCurrentEncoderRange() {
        val values = mutableListOf<MediaConvertVideoSettings>()
        compose.setContent { SubtitleEditComposeTheme {
            MediaConvertVideoSettingDialog(MediaConvertVideoSetting.CRF, MediaConvertVideoSettings(), "libx264",
                listOf("yuv420p"), null, false, {}, { values += it })
        } }
        compose.onNodeWithTag("video-setting-option-custom").performScrollTo().performClick()
        compose.onNodeWithTag("video-setting-custom").performScrollTo().performTextReplacement("52")
        compose.onNodeWithTag("video-setting-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("video-setting-custom").performTextReplacement("0")
        compose.onNodeWithTag("video-setting-confirm").performClick()
        assertEquals(0, values.single().crf)
    }

    @Test fun copyDisablesEncodingControlsWithoutChangingSettings() {
        val values = mutableListOf<MediaConvertVideoSettings>()
        compose.setContent { SubtitleEditComposeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MediaConvertVideoMoreSettings(MediaConvertVideoSettings(), "copy", MediaConvertBitrateMode.AUTO,
                    null, null, false, false, { values += it })
            }
        } }
        compose.onNodeWithTag("video-more-ROTATION").assertIsNotEnabled()
        compose.onNodeWithTag("video-more-CRF").assertIsNotEnabled()
        compose.onNodeWithTag("video-more-TWO_PASS").assertIsNotEnabled()
        assertTrue(values.isEmpty())
    }

    @Test fun percentageSizeDisplaysPreviewAndOnlyCommitsOnDone() {
        val values = mutableListOf<MediaConvertVideoSettings>()
        compose.setContent { SubtitleEditComposeTheme {
            MediaConvertVideoSettingDialog(MediaConvertVideoSetting.SIZE, MediaConvertVideoSettings(), "libx264",
                listOf("yuv420p"), null, true, {}, { values += it }, sourceFileSizeBytes = 100L * 1_048_576)
        } }
        compose.onNodeWithTag("video-setting-option-50").performScrollTo().performClick()
        compose.onNodeWithText(ByteSizeFormat.format(50L * 1_048_576)).assertExists()
        assertTrue(values.isEmpty())
        compose.onNodeWithTag("video-setting-confirm").performClick()
        assertEquals(50, values.single().sizeLimitPercent)
    }

    @Test fun filterListMatchesReferenceAndCancelDiscardsDraft() {
        var dismissed = false
        val values = mutableListOf<MediaConvertVideoSettings>()
        compose.setContent { SubtitleEditComposeTheme {
            MediaConvertVideoSettingDialog(MediaConvertVideoSetting.FILTER, MediaConvertVideoSettings(), "libx264",
                listOf("yuv420p"), null, false, { dismissed = true }, { values += it })
        } }
        for (filter in MediaConvertVideoFilter.entries) {
            compose.onNodeWithTag("video-setting-option-${filter.name}").performScrollTo().assertExists()
        }
        compose.onNodeWithTag("video-setting-option-GOLD").performClick()
        compose.onNodeWithText("取消").performClick()
        assertTrue(dismissed)
        assertTrue(values.isEmpty())
    }

    @Test fun binarySettingsUseDropdownAndApplyImmediately() {
        val settings = mutableStateOf(MediaConvertVideoSettings())
        compose.setContent { SubtitleEditComposeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MediaConvertVideoMoreSettings(settings.value, "libx264", MediaConvertBitrateMode.CUSTOM,
                    null, null, false, false, { settings.value = it })
            }
        } }
        for (setting in listOf("FLIP_HORIZONTAL", "FLIP_VERTICAL", "TWO_PASS")) {
            compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("video-more-$setting"))).performScrollTo().performClick()
            compose.onNode(hasText("开启") and hasAnyAncestor(isPopup())).performClick()
            compose.onNodeWithTag("video-setting-confirm").assertDoesNotExist()
        }
        assertTrue(settings.value.flipHorizontal)
        assertTrue(settings.value.flipVertical)
        assertTrue(settings.value.twoPass)
    }
}
