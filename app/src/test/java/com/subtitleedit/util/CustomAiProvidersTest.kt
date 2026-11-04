package com.subtitleedit.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomAiProvidersTest {
    @Test
    fun everyTemplateCreatesAnIndependentIdentityWithTheSameFields() {
        AiProviderConfig.providers.forEach { template ->
            val added = CustomAiProviders.create("  测试 ${template.displayName}  ", template.id)
            val another = CustomAiProviders.create("另一平台", template.id)

            assertTrue(added.isUserAdded)
            assertNotEquals(template.id, added.id)
            assertNotEquals(another.id, added.id)
            assertEquals("测试 ${template.displayName}", added.displayName)
            assertEquals(template, added.copy(id = template.id, displayName = template.displayName))
        }
    }

    @Test
    fun metadataRoundTripPreservesIdentityOrderAndTemplates() {
        val custom = AiProviderConfig.providers.map { CustomAiProviders.create("副本 ${it.displayName}", it.id) }
        val restored = CustomAiProviders.decode(CustomAiProviders.encode(AiProviderConfig.providers + custom))

        assertEquals(custom, restored)
        assertEquals(custom.last().id, restored.last().id)
        assertTrue(restored.last().isLocal)
        assertEquals(
            custom[1],
            AiProviderConfig.getProvider(custom[1].id, AiProviderConfig.providers + restored)
        )
    }

    @Test
    fun invalidOrDuplicateBackupEntriesCannotReplaceExistingPlatforms() {
        fun entry(id: String, name: String, template: String = AiProviderConfig.CUSTOM) = JSONObject()
            .put("id", id).put("name", name).put("template", template)
        val json = JSONArray()
            .put(entry(AiProviderConfig.OPENAI, "覆盖内置平台"))
            .put(entry("user_unknown", "未知模板", "unknown"))
            .put(entry("user_blank", "  "))
            .put(entry("user_builtin_name", "openai"))
            .put(entry("user_first", "我的平台"))
            .put(entry("user_first", "重复标识"))
            .put(entry("user_second", " 我的平台 "))
            .put(entry("user_last", "另一平台", AiProviderConfig.LOCAL))

        val restored = CustomAiProviders.decode(json.toString())

        assertEquals(listOf("user_first", "user_last"), restored.map { it.id })
        assertEquals(listOf("我的平台", "另一平台"), restored.map { it.displayName })
        assertTrue(restored.last().isLocal)
        assertFalse(AiProviderConfig.getProvider(AiProviderConfig.OPENAI).isUserAdded)
    }

    @Test
    fun invalidMetadataDoesNotPreventLoadingBuiltInPlatforms() {
        listOf("", "null", "{}", "broken", "[null,1,\"text\"]").forEach {
            assertTrue(CustomAiProviders.decode(it).isEmpty())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCustomPlatformCannotBeUsedAsAnotherTemplate() {
        val custom = CustomAiProviders.create("自建平台", AiProviderConfig.CUSTOM)
        CustomAiProviders.create("副本", custom.id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankNamesAreRejected() {
        CustomAiProviders.create("  ", AiProviderConfig.OPENAI)
    }
}
