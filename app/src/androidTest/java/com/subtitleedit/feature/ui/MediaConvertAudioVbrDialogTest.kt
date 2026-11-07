package com.subtitleedit.feature.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaConvertAudioVbr
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaConvertAudioVbrDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun qualityOnlyCommitsOnDoneAndOffCanBeRestored() {
        val saved = mutableListOf<Int>()
        compose.setContent { SubtitleEditComposeTheme { MediaConvertAudioVbrDialog(2, {}, { saved += it }) } }
        compose.onNodeWithTag("audio-vbr-option-2").assertIsSelected()
        compose.onNodeWithTag("audio-vbr-option-9").performScrollTo().performClick()
        assertTrue(saved.isEmpty())
        compose.onNodeWithTag("audio-vbr-confirm").performClick()
        assertEquals(listOf(9), saved)
        compose.onNodeWithTag("audio-vbr-option--1").performScrollTo().performClick()
        compose.onNodeWithTag("audio-vbr-confirm").performClick()
        assertEquals(listOf(9, MediaConvertAudioVbr.OFF), saved)
    }

    @Test
    fun cancelDiscardsTheChangedQuality() {
        var dismissed = false
        val saved = mutableListOf<Int>()
        compose.setContent { SubtitleEditComposeTheme { MediaConvertAudioVbrDialog(MediaConvertAudioVbr.OFF, { dismissed = true }, { saved += it }) } }
        compose.onNodeWithTag("audio-vbr-option-0").performClick()
        compose.onNodeWithText("取消").performClick()
        assertTrue(dismissed)
        assertTrue(saved.isEmpty())
    }
}
