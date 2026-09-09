package com.subtitleedit.repository

import android.content.ContentResolver
import android.content.Context
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.WhisperRecognizer

internal interface SpeechRecognitionService {
    fun createRecognizer(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String = "",
        tokensPath: String,
        vadModelPath: String = "",
        useVad: Boolean = true,
        language: String = "auto",
        contentResolver: ContentResolver,
        context: Context,
        modelType: String = SettingsManager.ASR_MODEL_WHISPER,
        tokenTimestampExperiment: Boolean = false,
        tokenTimestampGapMs: Int = 500
    ): WhisperRecognizer
}
