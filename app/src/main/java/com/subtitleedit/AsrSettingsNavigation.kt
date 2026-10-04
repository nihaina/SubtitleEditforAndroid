package com.subtitleedit

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager

internal object AsrSettingsNavigation {
    fun open(context: Context, settings: SettingsManager = SettingsManager.getInstance(context)) {
        val destination = when (settings.getAsrModelType()) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> SenseVoiceSettingsActivity::class.java
            SettingsManager.ASR_MODEL_PARAKEET_TDT,
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> ParakeetSettingsActivity::class.java
            SettingsManager.ASR_MODEL_QWEN3_ASR -> Qwen3AsrSettingsActivity::class.java
            SettingsManager.ASR_MODEL_WHISPER -> WhisperSettingsActivity::class.java
            else -> null
        }
        if (destination == null) {
            OverwritingToast.makeText(
                context,
                context.getString(R.string.asr_settings_no_configuration_page),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            context.startActivity(Intent(context, destination))
        }
    }
}
