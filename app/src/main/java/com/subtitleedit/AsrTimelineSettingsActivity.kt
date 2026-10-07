package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.subtitleedit.ui.settings.AsrTimelineSettingsScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager

abstract class AsrTimelineSettingsActivity : AppComposeActivity() {

    protected abstract val modelType: String
    protected abstract val modelName: String

    private val viewModel: AsrTimelineSettingsViewModel by viewModels {
        viewModelFactory {
            initializer { AsrTimelineSettingsViewModel(checkNotNull(this[APPLICATION_KEY]), modelType) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                AsrTimelineSettingsScreen(
                    title = stringResource(R.string.asr_timeline_settings_title, modelName),
                    state = state,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenVadSettings = {
                        startActivity(Intent(this, VadModelSettingsActivity::class.java))
                    },
                    onUseVadTimestampChanged = viewModel::setUseVadTimestamp,
                    onDynamicPaddingEnabledChanged = viewModel::setDynamicPaddingEnabled,
                    onFixedSegmentSecondsChanged = viewModel::setFixedSegmentSeconds,
                    onFixedSegmentSecondsTextChanged = viewModel::setFixedSegmentSecondsText,
                    onFixedVadSegmentationChanged = viewModel::setFixedVadSegmentation,
                    onTokenTimestampEnabledChanged = viewModel::setTokenTimestampEnabled,
                    onTokenTimestampMergeChanged = viewModel::setTokenTimestampMerge,
                    onSmartMergeChanged = viewModel::setSmartMerge,
                    onFilterLongMergeChanged = viewModel::setFilterLongMerge,
                    onMergeMaxCharactersChanged = viewModel::setMergeMaxCharacters,
                    onMergeGapChanged = viewModel::setMergeGap,
                    onMergeGapTextChanged = viewModel::setMergeGapText
                )
            }
        }
    }
}

class SenseVoiceSettingsActivity : AsrTimelineSettingsActivity() {
    override val modelType = SettingsManager.ASR_MODEL_SENSEVOICE
    override val modelName = "SenseVoice"
}

class ParakeetSettingsActivity : AsrTimelineSettingsActivity() {
    override val modelType: String by lazy {
        SettingsManager.getInstance(this).getAsrModelType()
    }
    override val modelName = "Parakeet"
}
