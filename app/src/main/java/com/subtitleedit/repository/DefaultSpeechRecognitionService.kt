package com.subtitleedit.repository

import android.content.ContentResolver
import android.content.Context
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.WhisperRecognizer

internal class DefaultSpeechRecognitionService : SpeechRecognitionService {
    override fun createRecognizer(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String,
        vadModelPath: String,
        useVad: Boolean,
        language: String,
        contentResolver: ContentResolver,
        context: Context,
        modelType: String,
        tokenTimestampExperiment: Boolean,
        tokenTimestampGapMs: Int
    ) = WhisperRecognizer(
        encoderPath = encoderPath,
        decoderPath = decoderPath,
        joinerPath = joinerPath,
        tokensPath = tokensPath,
        vadModelPath = vadModelPath,
        useVad = useVad,
        language = language,
        contentResolver = contentResolver,
        context = context,
        modelType = modelType,
        tokenTimestampExperiment = tokenTimestampExperiment,
        tokenTimestampGapMs = tokenTimestampGapMs
    )
}
