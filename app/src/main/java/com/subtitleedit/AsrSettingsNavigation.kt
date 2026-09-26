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
            SettingsManager.ASR_MODEL_WHISPER -> WhisperSettingsActivity::class.java
            else -> null
        }
        if (destination == null) {
            OverwritingToast.makeText(context, "Qwen3-ASR 配置页后续添加", Toast.LENGTH_SHORT).show()
        } else {
            context.startActivity(Intent(context, destination))
        }
    }
}
