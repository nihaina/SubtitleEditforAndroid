package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloaderQwen3AsrTest {
    @Test
    fun qwen3AsrVariantsUseTheirModelScopeDirectories() {
        assertEquals(
            listOf("model_0.6B", "model_1.7B"),
            ModelDownloader.QWEN3_ASR_MODELS.map { it.remoteModelDirectory }
        )
        assertEquals(
            listOf("0.6b", "1.7b"),
            ModelDownloader.QWEN3_ASR_MODELS.map { it.id }
        )
    }
}
