package com.subtitleedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTranslateQueueCodecTest {
    @Test
    fun roundTripsPausedFileProgressAndConfig() {
        val snapshot = AutoTranslateQueueSnapshot(
            punctuationPredictionEnabled = true,
            translationEnabled = true,
            workRunning = true,
            files = listOf(
                AutoTranslateFileSnapshot(
                    uri = "content://subtitle/1",
                    fileName = "episode.srt",
                    fileSize = 42L,
                    sessionId = "session-1",
                    status = "PAUSED",
                    processingStage = "TRANSLATION",
                    totalLines = 10,
                    processedLines = 4,
                    progressStage = "TRANSLATION",
                    documentContent = "1\n00:00:00,000 --> 00:00:01,000\n原文",
                    originalText = null,
                    punctuationPredictionCompleted = true,
                    translatedTexts = listOf("译文 1", "译文 2"),
                    translationResumeStart = 1,
                    translationBatchSize = 8,
                    translationConfig = PersistedTranslationConfig(
                        provider = "custom",
                        model = "model",
                        targetLanguage = "中文",
                        customPrompt = "prompt",
                        baseUrl = "https://example.test/v1",
                        reasoningLevel = "AUTO",
                        thinkingEnabled = true
                    ),
                    punctuationProvider = "custom",
                    punctuationEnabled = true,
                    translationEnabled = true,
                    overwriteOutput = true,
                    punctuationCompletedTexts = listOf("标点"),
                    textPunctuationPredictions = emptyList(),
                    textTranslations = emptyList(),
                    punctuationResumeHistory = true,
                    translationResumeHistory = true,
                    message = "AI 长时间无响应，正在自动重试 1/3"
                )
            )
        )

        val restored = AutoTranslateQueueCodec.decode(AutoTranslateQueueCodec.encode(snapshot))

        assertEquals(snapshot.punctuationPredictionEnabled, restored.punctuationPredictionEnabled)
        assertEquals(snapshot.translationEnabled, restored.translationEnabled)
        assertEquals(snapshot.workRunning, restored.workRunning)
        assertEquals(snapshot.files, restored.files)
        assertTrue(restored.files.single().translationResumeHistory)
        assertEquals(snapshot.files.single().message, restored.files.single().message)
    }
}
