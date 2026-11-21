package com.subtitleedit.feature.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaConvertDialogLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortChannelListWrapsWhileLongListKeepsItsConfirmationVisible() {
        val options = mutableStateOf(listOf(AppOption(0, "Original"), AppOption(1, "Mono")))
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertChannelDialog(0, options.value, "Stereo", false, {}, {})
            }
        }

        val confirm = compose.onNodeWithTag("channel-confirm")
        confirm.assertIsDisplayed()
        val shortHeight = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.height

        compose.runOnIdle {
            options.value = (0..40).map { AppOption(it, "Channels ${it + 1}") }
        }
        confirm.assertIsDisplayed()
        val longHeight = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.height
        assertTrue("Short dialog ($shortHeight px) should be smaller than long dialog ($longHeight px)", shortHeight < longHeight)
    }
}
