package com.subtitleedit.feature.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaConvertSubtitleType
import com.subtitleedit.MediaConvertSourceSubtitle
import com.subtitleedit.MediaConvertEmbeddedLyric
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
            MediaConvertSubtitlePage(single.value, true, emptyList(), MediaConvertSubtitleType.SOFT, allowed.value,
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

    @Test fun addedSoftSubtitlesAreListedAndRemovedIndependently() {
        val names = mutableStateOf(listOf("Chinese.srt", "English.ass"))
        var picks = 0
        compose.setContent { SubtitleEditComposeTheme {
            MediaConvertSubtitlePage(true, true, names.value, MediaConvertSubtitleType.SOFT, setOf("srt", "ass"),
                false, true, { picks++ }, { removed -> names.value = names.value.filterIndexed { index, _ -> index != removed } }, {})
        } }
        compose.onNodeWithText("Chinese.srt").assertExists()
        compose.onNodeWithText("English.ass").assertExists()
        compose.onNodeWithTag("convert-subtitle-pick").performClick()
        compose.onNodeWithTag("convert-subtitle-remove-0").performClick()
        compose.onNodeWithText("Chinese.srt").assertDoesNotExist()
        compose.onNodeWithText("English.ass").assertExists()
        compose.runOnIdle { assertEquals(1, picks) }
    }

    @Test fun audioOnlyOffersEmbeddedLyricsAndImmediatelyReflectsFormatCompatibility() {
        val allowed = mutableStateOf(setOf("lrc", "srt"))
        val maxFiles = mutableStateOf(Int.MAX_VALUE)
        compose.setContent { SubtitleEditComposeTheme {
            MediaConvertSubtitlePage(true, true, listOf("Chinese.lrc", "English.lrc"), MediaConvertSubtitleType.SOFT,
                allowed.value, false, true, {}, {}, {}, isAudioOutput = true, maxSubtitleFiles = maxFiles.value)
        } }
        compose.onNodeWithText("内嵌歌词").assertExists()
        compose.onNodeWithText("外挂式字幕").assertDoesNotExist()
        compose.onNodeWithText("嵌入式字幕").assertDoesNotExist()
        compose.onNodeWithTag("convert-subtitle-pick").assertIsEnabled()
        compose.runOnIdle { allowed.value = emptySet() }
        compose.onNodeWithTag("convert-subtitle-pick").assertIsNotEnabled()
        compose.onNodeWithText("当前音频格式无法写入受支持的歌词标签，请选择 MP3、AAC、FLAC、OGG、Opus、M4A、M4B、WMA、WavPack、WAV 或 MKA。").assertExists()
        compose.runOnIdle { allowed.value = setOf("lrc"); maxFiles.value = 1 }
        compose.onNodeWithText("当前格式支持一个歌词标签，保存完整 LRC 文本。其他字幕格式会先转换为 LRC；歌词同步显示取决于播放器。").assertExists()
    }

    @Test fun originalTracksShowMetadataAndWhetherTheSelectedOutputCanKeepThem() {
        val type = mutableStateOf(MediaConvertSubtitleType.SOFT)
        val support = mutableStateOf(listOf(true, false))
        compose.setContent { SubtitleEditComposeTheme {
            MediaConvertSubtitlePage(true, true, emptyList(), type.value, setOf("srt"), false, true, {}, {}, {},
                sourceSubtitleTracks = listOf(MediaConvertSourceSubtitle(2, "subrip", "zho", "Chinese", true),
                    MediaConvertSourceSubtitle(3, "hdmv_pgs_subtitle", "eng", "English")),
                sourceSubtitleSupport = support.value)
        } }
        compose.onNodeWithText("已有字幕轨").assertExists()
        compose.onNodeWithTag("convert-source-subtitle-2").assertExists()
        compose.onNodeWithText("Chinese").assertExists()
        compose.onNodeWithText("zho · subrip · 默认").assertExists()
        compose.onNodeWithText("转换时保留").assertExists()
        compose.onNodeWithText("当前输出格式不支持此字幕轨，转换时不保留").assertExists()
        compose.runOnIdle { type.value = MediaConvertSubtitleType.BURN_IN; support.value = listOf(false, false) }
        compose.onAllNodesWithText("烧录模式不保留已有字幕轨").assertCountEquals(2)
    }

    @Test fun sourceTypeChoosesOneExistingTrackSectionAndLyricsOpenThroughViewActions() {
        val longLyrics = (1..8).joinToString("\n") { "[00:0$it.00]歌词第 $it 行" }
        val audioSource = mutableStateOf(true)
        val audioOutput = mutableStateOf(false)
        val viewed = mutableListOf<Int>()
        val lyrics = mutableStateOf(listOf(
            MediaConvertEmbeddedLyric("USLT", "Chinese lyrics", "zho", longLyrics, true),
            MediaConvertEmbeddedLyric("SYLT", "English lyrics", "eng", "[00:01.00]Sea breeze", true),
            MediaConvertEmbeddedLyric("LYRICS", "Plain lyrics", "", "A plain lyric", false)
        ))
        compose.setContent { SubtitleEditComposeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MediaConvertSubtitlePage(true, true, emptyList(), MediaConvertSubtitleType.SOFT,
                    setOf("lrc"), false, true, {}, {}, {}, isAudioOutput = audioOutput.value,
                    sourceSubtitleTracks = listOf(MediaConvertSourceSubtitle(2, "subrip", "zho", "Video captions", true)),
                    sourceEmbeddedLyrics = lyrics.value, sourceIsAudio = audioSource.value,
                    onViewEmbeddedLyric = { viewed += it })
            }
        } }

        compose.onNodeWithText("已有内嵌歌词").assertExists()
        compose.onNodeWithText("已有字幕轨").assertDoesNotExist()
        compose.onNodeWithText("Video captions").assertDoesNotExist()
        compose.onNodeWithText("Chinese lyrics").assertExists()
        compose.onNodeWithText("USLT · zho · 带时间轴").assertExists()
        compose.onNodeWithText("SYLT · eng · 带时间轴").assertExists()
        compose.onNodeWithText("LYRICS · 纯文本").assertExists()
        compose.onAllNodesWithText("查看").assertCountEquals(3)
        compose.onNodeWithText("展开歌词").assertDoesNotExist()
        compose.onNodeWithText("收起歌词").assertDoesNotExist()
        compose.onNodeWithText(longLyrics).assertDoesNotExist()
        compose.onNodeWithText("[00:01.00]Sea breeze").assertDoesNotExist()
        compose.onNodeWithText("A plain lyric").assertDoesNotExist()
        for (index in 0..2) compose.onNodeWithTag("convert-source-lyric-view-$index").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(0, 1, 2), viewed) }

        // Output format changes must not change which existing-source section is shown.
        compose.runOnIdle { audioOutput.value = true }
        compose.onNodeWithText("已有内嵌歌词").assertExists()
        compose.onNodeWithText("已有字幕轨").assertDoesNotExist()
        compose.runOnIdle { audioSource.value = false }
        compose.onNodeWithText("已有字幕轨").assertExists()
        compose.onNodeWithText("Video captions").assertExists()
        compose.onNodeWithText("已有内嵌歌词").assertDoesNotExist()
        compose.onNodeWithTag("convert-source-lyric-0").assertDoesNotExist()
        compose.onAllNodesWithText("查看").assertCountEquals(0)

        compose.runOnIdle { audioSource.value = true; lyrics.value = emptyList() }
        compose.onNodeWithText("未检测到内嵌歌词。").assertExists()
        compose.onNodeWithText("已有字幕轨").assertDoesNotExist()
        compose.onNodeWithTag("convert-source-lyric-0").assertDoesNotExist()
        compose.onNodeWithText("Chinese lyrics").assertDoesNotExist()
        compose.onNodeWithText("English lyrics").assertDoesNotExist()
        compose.onNodeWithText("Plain lyrics").assertDoesNotExist()
    }
}
