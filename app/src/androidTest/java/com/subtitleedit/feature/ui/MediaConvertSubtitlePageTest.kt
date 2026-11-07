package com.subtitleedit.feature.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaConvertSubtitleType
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaConvertSubtitlePageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun batchSelectionAndUnsupportedModesDisablePickingWithoutLosingTypeControl() {
        val single = mutableStateOf(false)
        val allowed = mutableStateOf(setOf("srt", "ass"))
        var picks = 0
        compose.setContent { SubtitleEditComposeTheme {
            MediaConvertSubtitlePage(single.value, true, null, MediaConvertSubtitleType.SOFT, allowed.value,
                false, true, { picks++ }, {}, {})
        } }
        compose.onNodeWithTag("convert-subtitle-pick").assertIsNotEnabled()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("convert-subtitle-type"))).assertIsNotEnabled()
        compose.runOnIdle { single.value = true }
        compose.onNodeWithTag("convert-subtitle-pick").performClick()
        assertEquals(1, picks)
        compose.runOnIdle { allowed.value = emptySet() }
        compose.onNodeWithTag("convert-subtitle-pick").assertIsNotEnabled()
        compose.onNodeWithText("当前输出视频格式不支持封装可开关的文本字幕轨，请切换为嵌入式字幕或选择 MKV、MP4/MOV、WebM 等格式。").assertExists()
    }
}
