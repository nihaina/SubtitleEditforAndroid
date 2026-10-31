package com.subtitleedit.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsBackupManagerTest {

    @Test
    fun localLlmSettingsAreExportedAndRestored() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager.getInstance(context)
        val original = LlmSettingsSnapshot.from(settings)
        try {
            settings.setLlmRepackEnabled(false)
            settings.setLlmContextSize(8192)
            settings.setLlmThinkingBudget(1536)
            settings.setLlmTemperature(0.35f)
            settings.setLlmTopP(0.72f)
            settings.setLlmRepetitionPenalty(1.25f)

            val backup = JSONObject(SettingsBackupManager.export(context))
                .getJSONObject("settings")
            assertTypedValue(backup, "llm_repack_enabled", "boolean", false)
            assertTypedValue(backup, "llm_context_size", "int", 8192)
            assertTypedValue(backup, "llm_thinking_budget", "int", 1536)
            assertTypedValue(backup, "llm_temperature", "float", 0.35f)
            assertTypedValue(backup, "llm_top_p", "float", 0.72f)
            assertTypedValue(backup, "llm_repetition_penalty", "float", 1.25f)

            settings.setLlmRepackEnabled(true)
            settings.setLlmContextSize(2048)
            settings.setLlmThinkingBudget(512)
            settings.setLlmTemperature(0.8f)
            settings.setLlmTopP(0.95f)
            settings.setLlmRepetitionPenalty(1.0f)

            SettingsBackupManager.restore(context, JSONObject()
                .put("schemaVersion", 1)
                .put("settings", backup)
                .toString())

            assertEquals(false, settings.isLlmRepackEnabled())
            assertEquals(8192, settings.getLlmContextSize())
            assertEquals(1536, settings.getLlmThinkingBudget())
            assertEquals(0.35f, settings.getLlmTemperature(), 0.0001f)
            assertEquals(0.72f, settings.getLlmTopP(), 0.0001f)
            assertEquals(1.25f, settings.getLlmRepetitionPenalty(), 0.0001f)
        } finally {
            original.restore(settings)
        }
    }

    @Test
    fun backupWithoutLocalLlmSettingsLeavesExistingValuesUntouched() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager.getInstance(context)
        val original = LlmSettingsSnapshot.from(settings)
        try {
            settings.setLlmRepackEnabled(false)
            settings.setLlmContextSize(8192)
            settings.setLlmThinkingBudget(1536)
            settings.setLlmTemperature(0.35f)
            settings.setLlmTopP(0.72f)
            settings.setLlmRepetitionPenalty(1.25f)

            SettingsBackupManager.restore(context, JSONObject()
                .put("schemaVersion", 1)
                .put("settings", JSONObject())
                .toString())

            // A backup from before these settings existed does not overwrite
            // the current values; a fresh install still gets the getter defaults.
            assertEquals(false, settings.isLlmRepackEnabled())
            assertEquals(8192, settings.getLlmContextSize())
            assertEquals(1536, settings.getLlmThinkingBudget())
            assertEquals(0.35f, settings.getLlmTemperature(), 0.0001f)
            assertEquals(0.72f, settings.getLlmTopP(), 0.0001f)
            assertEquals(1.25f, settings.getLlmRepetitionPenalty(), 0.0001f)
        } finally {
            original.restore(settings)
        }
    }

    @Test
    fun demixOrtSettingsAreExportedAndRestored() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager.getInstance(context)
        val original = DemixOrtSettingsSnapshot.from(settings)
        try {
            settings.setDemixOrtIntraOpThreads(7)
            settings.setDemixOrtChunkParallelEnabled(true)
            settings.setDemixOrtGraphOptimizationEnabled(false)
            settings.setDemixOrtCpuArenaEnabled(false)

            val backup = JSONObject(SettingsBackupManager.export(context))
                .getJSONObject("settings")
            assertTypedValue(backup, "demix_ort_intra_op_threads", "int", 7)
            assertTypedValue(backup, "demix_ort_chunk_parallel", "boolean", true)
            assertTypedValue(backup, "demix_ort_graph_optimization", "boolean", false)
            assertTypedValue(backup, "demix_ort_cpu_arena", "boolean", false)

            original.restore(settings)
            SettingsBackupManager.restore(context, JSONObject()
                .put("schemaVersion", 1)
                .put("settings", backup)
                .toString())

            assertEquals(7, settings.getDemixOrtIntraOpThreads())
            assertEquals(true, settings.isDemixOrtChunkParallelEnabled())
            assertEquals(false, settings.isDemixOrtGraphOptimizationEnabled())
            assertEquals(false, settings.isDemixOrtCpuArenaEnabled())
        } finally {
            original.restore(settings)
        }
    }

    @Test
    fun asrPunctuationSplitSettingsAreExportedAndRestoredPerModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager.getInstance(context)
        val modelTypes = listOf(
            SettingsManager.ASR_MODEL_SENSEVOICE,
            SettingsManager.ASR_MODEL_PARAKEET_TDT
        )
        val original = modelTypes.associateWith { type ->
            PunctuationSplitSnapshot(
                enabled = settings.isSpeechTokenTimestampPunctuationSplitEnabled(type),
                punctuation = settings.getSpeechTokenTimestampSplitPunctuation(type)
            )
        }
        try {
            settings.setSpeechTokenTimestampPunctuationSplitEnabled(
                enabled = false,
                type = SettingsManager.ASR_MODEL_SENSEVOICE
            )
            settings.setSpeechTokenTimestampSplitPunctuation(
                punctuation = listOf("。", "？", "["),
                type = SettingsManager.ASR_MODEL_SENSEVOICE
            )
            settings.setSpeechTokenTimestampPunctuationSplitEnabled(
                enabled = true,
                type = SettingsManager.ASR_MODEL_PARAKEET_TDT
            )
            settings.setSpeechTokenTimestampSplitPunctuation(
                punctuation = listOf("！", "!", "…"),
                type = SettingsManager.ASR_MODEL_PARAKEET_TDT
            )

            val backup = JSONObject(SettingsBackupManager.export(context))
                .getJSONObject("settings")
            assertTypedValue(
                backup,
                "asr_timeline_sensevoice_token_merge_punctuation_split_enabled",
                "boolean",
                false
            )
            assertTypedStringArray(
                backup,
                "asr_timeline_sensevoice_token_merge_split_punctuation",
                listOf("。", "？", "[")
            )
            assertTypedValue(
                backup,
                "asr_timeline_parakeet_token_merge_punctuation_split_enabled",
                "boolean",
                true
            )
            assertTypedStringArray(
                backup,
                "asr_timeline_parakeet_token_merge_split_punctuation",
                listOf("！", "!", "…")
            )

            settings.setSpeechTokenTimestampPunctuationSplitEnabled(
                enabled = true,
                type = SettingsManager.ASR_MODEL_SENSEVOICE
            )
            settings.setSpeechTokenTimestampSplitPunctuation(
                punctuation = listOf("，"),
                type = SettingsManager.ASR_MODEL_SENSEVOICE
            )
            settings.setSpeechTokenTimestampPunctuationSplitEnabled(
                enabled = false,
                type = SettingsManager.ASR_MODEL_PARAKEET_TDT
            )
            settings.setSpeechTokenTimestampSplitPunctuation(
                punctuation = listOf("。"),
                type = SettingsManager.ASR_MODEL_PARAKEET_TDT
            )

            SettingsBackupManager.restore(
                context,
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("settings", backup)
                    .toString()
            )

            assertEquals(
                false,
                settings.isSpeechTokenTimestampPunctuationSplitEnabled(
                    SettingsManager.ASR_MODEL_SENSEVOICE
                )
            )
            assertEquals(
                listOf("。", "？", "["),
                settings.getSpeechTokenTimestampSplitPunctuation(
                    SettingsManager.ASR_MODEL_SENSEVOICE
                )
            )
            assertEquals(
                true,
                settings.isSpeechTokenTimestampPunctuationSplitEnabled(
                    SettingsManager.ASR_MODEL_PARAKEET_TDT
                )
            )
            assertEquals(
                listOf("！", "!", "…"),
                settings.getSpeechTokenTimestampSplitPunctuation(
                    SettingsManager.ASR_MODEL_PARAKEET_TDT
                )
            )
        } finally {
            original.forEach { (type, value) ->
                settings.setSpeechTokenTimestampPunctuationSplitEnabled(value.enabled, type)
                settings.setSpeechTokenTimestampSplitPunctuation(value.punctuation, type)
            }
        }
    }

    private data class LlmSettingsSnapshot(
        val repackEnabled: Boolean,
        val contextSize: Int,
        val thinkingBudget: Int,
        val temperature: Float,
        val topP: Float,
        val repetitionPenalty: Float
    ) {
        fun restore(settings: SettingsManager) {
            settings.setLlmRepackEnabled(repackEnabled)
            settings.setLlmContextSize(contextSize)
            settings.setLlmThinkingBudget(thinkingBudget)
            settings.setLlmTemperature(temperature)
            settings.setLlmTopP(topP)
            settings.setLlmRepetitionPenalty(repetitionPenalty)
        }

        companion object {
            fun from(settings: SettingsManager) = LlmSettingsSnapshot(
                repackEnabled = settings.isLlmRepackEnabled(),
                contextSize = settings.getLlmContextSize(),
                thinkingBudget = settings.getLlmThinkingBudget(),
                temperature = settings.getLlmTemperature(),
                topP = settings.getLlmTopP(),
                repetitionPenalty = settings.getLlmRepetitionPenalty()
            )
        }
    }

    private data class DemixOrtSettingsSnapshot(
        val intraOpThreads: Int,
        val chunkParallelEnabled: Boolean,
        val graphOptimizationEnabled: Boolean,
        val cpuArenaEnabled: Boolean
    ) {
        fun restore(settings: SettingsManager) {
            settings.setDemixOrtIntraOpThreads(intraOpThreads)
            settings.setDemixOrtChunkParallelEnabled(chunkParallelEnabled)
            settings.setDemixOrtGraphOptimizationEnabled(graphOptimizationEnabled)
            settings.setDemixOrtCpuArenaEnabled(cpuArenaEnabled)
        }

        companion object {
            fun from(settings: SettingsManager) = DemixOrtSettingsSnapshot(
                intraOpThreads = settings.getDemixOrtIntraOpThreads(),
                chunkParallelEnabled = settings.isDemixOrtChunkParallelEnabled(),
                graphOptimizationEnabled = settings.isDemixOrtGraphOptimizationEnabled(),
                cpuArenaEnabled = settings.isDemixOrtCpuArenaEnabled()
            )
        }
    }

    private data class PunctuationSplitSnapshot(
        val enabled: Boolean,
        val punctuation: List<String>
    )

    private fun assertTypedValue(
        settings: JSONObject,
        key: String,
        type: String,
        expected: Any
    ) {
        val value = settings.getJSONObject(key)
        assertEquals(type, value.getString("type"))
        when (expected) {
            is Boolean -> assertEquals(expected, value.getBoolean("value"))
            is Int -> assertEquals(expected, value.getInt("value"))
            is Float -> assertEquals(expected.toDouble(), value.getDouble("value"), 0.0001)
            else -> assertTrue("Unsupported test value type", false)
        }
    }

    private fun assertTypedStringArray(
        settings: JSONObject,
        key: String,
        expected: List<String>
    ) {
        val value = settings.getJSONObject(key)
        assertEquals("string", value.getString("type"))
        val actual = JSONArray(value.getString("value"))
        assertEquals(expected.size, actual.length())
        expected.forEachIndexed { index, punctuation ->
            assertEquals(punctuation, actual.getString(index))
        }
    }
}
