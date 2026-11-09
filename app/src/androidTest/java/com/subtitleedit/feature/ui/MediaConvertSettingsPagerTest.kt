package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaConvertSettingsPagerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun localPagerSupportsSwipeTabsAndRestorationInsideTheScrollableToolPage() {
        val restoration = StateRestorationTester(compose)
        val enabled = mutableStateOf(true)
        restoration.setContent {
            SubtitleEditComposeTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("源文件区域")
                    MediaConvertSettingsPager(
                        enabled = enabled.value,
                        moreContent = { Text("更多设置内容", Modifier.fillMaxWidth().height(160.dp)) },
                        advancedContent = { Text("高级选项内容") }
                    )
                    Text("输出目录区域")
                }
            }
        }
        compose.onNodeWithTag("convert-settings-tab-0").assertIsSelected()
        compose.onNodeWithTag("convert-settings-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("convert-settings-tab-1").assertIsSelected()
        compose.onNodeWithText("源文件区域").assertIsDisplayed()
        compose.onNodeWithText("高级选项内容").assertIsDisplayed()
        compose.onNodeWithText("输出目录区域").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("convert-settings-tab-1").assertIsSelected()
        compose.onNodeWithTag("convert-settings-tab-0").performClick().assertIsSelected()
        compose.onNodeWithText("更多设置内容").assertIsDisplayed()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("convert-settings-tab-0").assertIsNotEnabled()
        compose.onNodeWithTag("convert-settings-tab-1").assertIsNotEnabled()
    }

    @Test
    fun subtitlePageRestoresForVideoAndReturnsToAdvancedWhenThePageIsHidden() {
        val videoOutput = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SubtitleEditComposeTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MediaConvertSettingsPager(true, { Text("更多设置内容") }, { Text("高级选项内容") },
                        showSubtitlePage = videoOutput.value, subtitleContent = { Text("字幕配置内容") })
                }
            }
        }
        compose.onNodeWithTag("convert-settings-tab-2").performClick().assertIsSelected()
        compose.onNodeWithText("字幕配置内容").assertIsDisplayed()
        compose.onNodeWithTag("convert-settings-tab-3").assertDoesNotExist()
        compose.onNodeWithText("信息").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("convert-settings-tab-2").assertIsSelected()
        compose.runOnIdle { videoOutput.value = false }
        compose.onNodeWithTag("convert-settings-tab-2").assertDoesNotExist()
        compose.onNodeWithTag("convert-settings-tab-1").assertIsSelected()
        compose.onNodeWithText("高级选项内容").assertIsDisplayed()
    }

    @Test
    fun audioOutputShowsAdvancedAndSubtitlesAndRestoresTheSubtitlePage() {
        val audioOutput = mutableStateOf(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SubtitleEditComposeTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MediaConvertSettingsPager(
                        enabled = true,
                        moreContent = { Text("更多设置内容") },
                        advancedContent = { Text("高级选项内容") },
                        showSubtitlePage = true,
                        subtitleContent = { Text("字幕配置内容") },
                        isAudioOutput = audioOutput.value
                    )
                }
            }
        }

        compose.onNodeWithTag("convert-settings-tab-1").performClick().assertIsSelected()
        compose.runOnIdle { audioOutput.value = true }
        compose.onNodeWithText("更多设置").assertDoesNotExist()
        compose.onNodeWithText("字幕").assertExists()
        compose.onNodeWithText("信息").assertDoesNotExist()
        compose.onNodeWithTag("convert-settings-tab-0").assertIsSelected()
        compose.onNodeWithText("高级选项内容").assertIsDisplayed()
        compose.onNodeWithTag("convert-settings-tab-1").performClick().assertIsSelected()
        compose.onNodeWithText("字幕配置内容").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("convert-settings-tab-1").assertIsSelected()
        compose.onNodeWithText("字幕配置内容").assertIsDisplayed()
        compose.runOnIdle { audioOutput.value = false }
        compose.onNodeWithTag("convert-settings-tab-2").assertIsSelected()
        compose.onNodeWithText("字幕配置内容").assertIsDisplayed()
    }

    @Test
    fun switchingFromVideoOnlySettingsToAudioFallsBackToAdvancedOptions() {
        val audioOutput = mutableStateOf(false)
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertSettingsPager(
                    true, { Text("更多设置内容") }, { Text("高级选项内容") },
                    showSubtitlePage = true,
                    subtitleContent = { Text("字幕配置内容") },
                    isAudioOutput = audioOutput.value
                )
            }
        }

        compose.onNodeWithTag("convert-settings-tab-0").assertIsSelected()
        compose.runOnIdle { audioOutput.value = true }
        compose.onNodeWithTag("convert-settings-tab-0").assertIsSelected()
        compose.onNodeWithText("高级选项内容").assertIsDisplayed()
    }
}
