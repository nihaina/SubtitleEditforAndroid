package com.subtitleedit.feature.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemainingScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mediaConvertRequiresInputAndRoutesPicker() {
        val calls = mutableListOf<String>()
        compose.setContent {
            SubtitleEditComposeTheme {
                MediaConvertScreen(
                    sourceSummary = "",
                    sourceInfo = "",
                    outputDirectory = "Download",
                    videoFormats = listOf(MediaFormatOption("mp4", "MP4", false)),
                    audioFormats = emptyList(),
                    selectedFormat = null,
                    videoCodecs = emptyList(),
                    selectedVideoCodec = "",
                    audioCodecs = emptyList(),
                    selectedAudioCodec = "",
                    resolutions = listOf(AppOption(0, "Original")),
                    resolutionIndex = 0,
                    videoBitrate = "",
                    qualityOptions = listOf(AppOption("-1", "Original")),
                    selectedQualityId = "-1",
                    customQuality = "",
                    audioBitrate = "",
                    sampleRates = listOf(AppOption(0, "Original")),
                    sampleRateIndex = 0,
                    channels = listOf(AppOption(0, "Original")),
                    channelIndex = 0,
                    advancedExpanded = false,
                    isConverting = false,
                    canConvert = false,
                    canShare = false,
                    progress = 0,
                    log = "",
                    dialog = MediaConvertDialog.NONE,
                    onBack = {},
                    onPickFiles = { calls += "pick" },
                    onSelectOutputDirectory = {},
                    onFormatSelected = {},
                    onVideoCodecSelected = {},
                    onAudioCodecSelected = {},
                    onResolutionSelected = {},
                    onVideoBitrateChange = {},
                    onQualitySelected = {},
                    onCustomQualityChange = {},
                    onAudioBitrateChange = {},
                    onSampleRateSelected = {},
                    onChannelSelected = {},
                    onToggleAdvanced = {},
                    onConvert = { calls += "convert" },
                    onCancel = {},
                    onShare = {},
                    onOverwrite = {},
                    onAutoRename = {},
                    onDismissDialog = {}
                )
            }
        }
        compose.onNodeWithText("未选择文件").assertIsDisplayed()
        compose.onNodeWithText("开始转换").assertIsNotEnabled()
        compose.onNodeWithText("选择音视频文件（可多选）").performClick()
        assertEquals(listOf("pick"), calls)
    }

    @Test
    fun transcriptMatchStartRequiresBothFiles() {
        val calls = mutableListOf<String>()
        compose.setContent {
            SubtitleEditComposeTheme {
                TranscriptMatchScreen(
                    textFileName = "尚未选择文件",
                    audioFileName = "尚未选择文件",
                    outputDirectory = "Download",
                    pendingFiles = "请选择一个文本文件和一个音频文件",
                    formats = listOf("SRT"),
                    selectedFormat = "SRT",
                    languages = listOf("中文"),
                    selectedLanguage = "中文",
                    modelHint = "",
                    startEnabled = false,
                    isRunning = false,
                    isCancelling = false,
                    progress = 0,
                    progressText = "",
                    dialog = TranscriptMatchDialog.NONE,
                    dialogTitle = "",
                    dialogMessage = "",
                    onBack = {},
                    onSelectText = { calls += "text" },
                    onSelectAudio = { calls += "audio" },
                    onSelectOutputDirectory = {},
                    onFormatSelected = {},
                    onLanguageSelected = {},
                    onStartOrCancel = { calls += "start" },
                    onDialogConfirm = {},
                    onDialogDismiss = {}
                )
            }
        }
        compose.onNodeWithText("开始处理").assertIsNotEnabled()
        compose.onNodeWithText("选择文本文件").performClick()
        compose.onNodeWithText("选择音频文件").performClick()
        assertEquals(listOf("text", "audio"), calls)
    }

    @Test
    fun autoTranslateStartRoutesCallback() {
        val calls = mutableListOf<String>()
        compose.setContent {
            SubtitleEditComposeTheme {
                AutoTranslateScreen(
                    state = AutoTranslateUiState(),
                    onNavigateBack = {},
                    onSettings = {},
                    onSelectFiles = { calls += "files" },
                    onPunctuationPredictionChange = {},
                    onTranslationChange = {},
                    onSelectOutputDirectory = {},
                    onStart = { calls += "start" },
                    onRetry = {},
                    onRemove = {},
                    onConfirmRemove = {},
                    onDismissRemove = {},
                    onDismissOutputConflict = {},
                    onOverwriteOutput = {},
                    onRenameOutput = {},
                    onConfirmExit = {},
                    onDismissExit = {}
                )
            }
        }
        compose.onNodeWithText("添加文件").performClick()
        compose.onNodeWithText("开始处理").performClick()
        assertEquals(listOf("files", "start"), calls)
    }
}
