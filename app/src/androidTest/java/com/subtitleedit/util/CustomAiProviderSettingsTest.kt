package com.subtitleedit.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CustomAiProviderSettingsTest {
    @Test
    fun sameTemplateSettingsAreIndependentAndDeletingResetsOnlyAffectedSelections() {
        val settings = SettingsManager.getInstance(ApplicationProvider.getApplicationContext())
        val selections = listOf(settings.getAiProvider(), settings.getAiTranslationProvider(), settings.getAiPunctuationProvider())
        val builtinEndpoint = settings.getAiBaseUrl(AiProviderConfig.CUSTOM)
        val first = settings.addAiProvider("第一平台 ${UUID.randomUUID()}", AiProviderConfig.CUSTOM)
        val second = settings.addAiProvider("第二平台 ${UUID.randomUUID()}", AiProviderConfig.CUSTOM)
        try {
            settings.setAiApiKey(first.id, "first-key")
            settings.setAiApiKey(second.id, "second-key")
            settings.setAiBaseUrl(first.id, "https://first.example/v1")
            settings.setAiBaseUrl(second.id, "https://second.example/v1")
            settings.setAiModel(first.id, "first-model")
            settings.setAiModel(second.id, "second-model")
            settings.setAiProvider(first.id)
            settings.setAiTranslationProvider(first.id)
            settings.setAiPunctuationProvider(second.id)

            assertEquals("first-key", settings.getAiApiKey(first.id))
            assertEquals("second-key", settings.getAiApiKey(second.id))
            assertEquals("https://first.example/v1", settings.getAiBaseUrl(first.id))
            assertEquals("https://second.example/v1", settings.getAiBaseUrl(second.id))
            assertEquals(builtinEndpoint, settings.getAiBaseUrl(AiProviderConfig.CUSTOM))
            assertTrue(settings.removeAiProvider(first.id))
            assertEquals(AiProviderConfig.SILICONFLOW, settings.getAiProvider())
            assertEquals(AiProviderConfig.SILICONFLOW, settings.getAiTranslationProvider())
            assertEquals(second.id, settings.getAiPunctuationProvider())
            assertEquals("second-model", settings.getAiModel(second.id))
            assertEquals("", settings.getAiApiKey(first.id))
            assertFalse(settings.exportSettingsSnapshot().keys.any { it.endsWith("_${first.id}") })
            assertFalse(settings.removeAiProvider(AiProviderConfig.OPENAI))
        } finally {
            settings.removeAiProvider(first.id)
            settings.removeAiProvider(second.id)
            settings.setAiProvider(selections[0])
            settings.setAiTranslationProvider(selections[1])
            settings.setAiPunctuationProvider(selections[2])
        }
    }

    @Test
    fun backupRestoresPlatformMetadataScopedSettingsSelectionsAndCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager.getInstance(context)
        val selections = listOf(settings.getAiProvider(), settings.getAiTranslationProvider(), settings.getAiPunctuationProvider())
        val remote = settings.addAiProvider("备份平台 ${UUID.randomUUID()}", AiProviderConfig.CUSTOM)
        val local = settings.addAiProvider("备份本地 ${UUID.randomUUID()}", AiProviderConfig.LOCAL)
        try {
            settings.setAiApiKey(remote.id, "backup-api-key")
            settings.setAiBaseUrl(remote.id, "https://backup.example/v1")
            settings.setAiModel(remote.id, "translate-model")
            settings.setAiPunctuationModel(remote.id, "punctuation-model")
            settings.setAiReasoningLevel(AiProviderConfig.ReasoningLevel.HIGH, remote.id)
            settings.setAiPunctuationReasoningLevel(AiProviderConfig.ReasoningLevel.LOW, remote.id)
            settings.setAiSubtitlesPerRequest(remote.id, 321)
            settings.setAiSubtitlesPerRequest(local.id, 37)
            settings.setAiLocalTranslationThinkingEnabled(true, local.id)
            settings.setAiLocalPunctuationThinkingEnabled(true, local.id)
            settings.setAiProvider(remote.id)
            settings.setAiTranslationProvider(remote.id)
            settings.setAiPunctuationProvider(local.id)

            val exported = JSONObject(SettingsBackupManager.export(context))
            val exportedSettings = exported.getJSONObject("settings")
            val aiSettings = JSONObject()
            exportedSettings.keys().forEach { key ->
                if (key == "ai_custom_providers" || key in setOf("ai_provider", "ai_translation_provider", "ai_punctuation_provider") ||
                    key.endsWith("_${remote.id}") || key.endsWith("_${local.id}")
                ) aiSettings.put(key, exportedSettings.get(key))
            }
            val apiKeys = exported.getJSONObject("credentials").getJSONObject("aiApiKeys")
            assertEquals("backup-api-key", apiKeys.getString(remote.id))
            assertTrue(exportedSettings.getJSONObject("ai_custom_providers").getString("value").contains(remote.id))

            settings.removeAiProvider(remote.id)
            settings.removeAiProvider(local.id)
            assertFalse(settings.getAiProviders().any { it.id == remote.id })

            SettingsBackupManager.restore(context, JSONObject()
                .put("schemaVersion", 1)
                .put("settings", aiSettings)
                .put("credentials", JSONObject().put("aiApiKeys", JSONObject().put(remote.id, apiKeys.getString(remote.id))))
                .toString())

            assertEquals(remote, settings.getAiProviderConfig(remote.id))
            assertEquals(local, settings.getAiProviderConfig(local.id))
            assertEquals("backup-api-key", settings.getAiApiKey(remote.id))
            assertEquals("https://backup.example/v1", settings.getAiBaseUrl(remote.id))
            assertEquals("translate-model", settings.getAiModel(remote.id))
            assertEquals("punctuation-model", settings.getAiPunctuationModel(remote.id))
            assertEquals(AiProviderConfig.ReasoningLevel.HIGH, settings.getAiReasoningLevel(remote.id))
            assertEquals(AiProviderConfig.ReasoningLevel.LOW, settings.getAiPunctuationReasoningLevel(remote.id))
            assertEquals(321, settings.getAiSubtitlesPerRequest(remote.id))
            assertEquals(37, settings.getAiSubtitlesPerRequest(local.id))
            assertTrue(settings.isAiLocalTranslationThinkingEnabled(local.id))
            assertTrue(settings.isAiLocalPunctuationThinkingEnabled(local.id))
            assertEquals(remote.id, settings.getAiProvider())
            assertEquals(remote.id, settings.getAiTranslationProvider())
            assertEquals(local.id, settings.getAiPunctuationProvider())
        } finally {
            settings.removeAiProvider(remote.id)
            settings.removeAiProvider(local.id)
            settings.setAiProvider(selections[0])
            settings.setAiTranslationProvider(selections[1])
            settings.setAiPunctuationProvider(selections[2])
        }
    }
}
