package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderConfigTest {

    @Test
    fun providers_areInSettingsSpinnerOrder() {
        assertEquals(
            listOf(
                AiProviderConfig.SILICONFLOW,
                AiProviderConfig.DEEPSEEK,
                AiProviderConfig.OPENAI,
                AiProviderConfig.CUSTOM
            ),
            AiProviderConfig.providers.map { it.id }
        )
    }

    @Test
    fun providers_haveDisplayNameUrlAndDefaultModel() {
        AiProviderConfig.providers.forEach { provider ->
            assertTrue("${provider.id} 缺少显示名", provider.displayName.isNotBlank())
            if (!provider.customEndpoint) {
                assertTrue("${provider.id} 的 baseUrl 应为 https", provider.baseUrl.startsWith("https://"))
            }
            assertTrue("${provider.id} 的官网地址应为 https", provider.websiteUrl.startsWith("https://"))
            assertTrue("${provider.id} 缺少默认模型", provider.defaultModel.isNotBlank())
        }
    }

    @Test
    fun providers_haveUniqueIds() {
        val ids = AiProviderConfig.providers.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun providers_withModelListContainDefaultModel() {
        AiProviderConfig.providers.filter { it.models.isNotEmpty() }.forEach { provider ->
            assertTrue(
                "${provider.id} 的默认模型不在候选列表中",
                provider.defaultModel in provider.models
            )
        }
    }

    @Test
    fun getProvider_knownId_returnsMatchingEntry() {
        assertEquals(AiProviderConfig.DEEPSEEK, AiProviderConfig.getProvider(AiProviderConfig.DEEPSEEK).id)
        assertEquals("DeepSeek", AiProviderConfig.getProvider(AiProviderConfig.DEEPSEEK).displayName)
    }

    @Test
    fun getProvider_unknownOrEmptyId_fallsBackToFirst() {
        assertSame(AiProviderConfig.providers.first(), AiProviderConfig.getProvider("不存在的服务商"))
        assertSame(AiProviderConfig.providers.first(), AiProviderConfig.getProvider(""))
    }

    @Test
    fun indexOf_matchesProviderPosition() {
        AiProviderConfig.providers.forEachIndexed { index, provider ->
            assertEquals(index, AiProviderConfig.indexOf(provider.id))
        }
    }

    @Test
    fun indexOf_unknownId_fallsBackToZero() {
        assertEquals(0, AiProviderConfig.indexOf("不存在的服务商"))
        assertEquals(0, AiProviderConfig.indexOf(""))
    }
}
