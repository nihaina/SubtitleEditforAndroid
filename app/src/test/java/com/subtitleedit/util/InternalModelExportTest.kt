package com.subtitleedit.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InternalModelExportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportsSenseVoiceBinaryTogetherWithTokens() = runBlocking {
        val source = temporary.newFolder("sensevoice")
        source.resolve("model.bin").writeText("binary")
        source.resolve("tokens.txt").writeText("tokens")
        val downloads = temporary.newFolder("downloads")

        val exported = InternalModelExport.export(
            source, downloads, InternalModelExport.Kind.SENSEVOICE_NPU_5
        ) { _, _ -> }
        val pair = InternalModelExport.completeSenseVoiceFiles(exported)
        assertNotNull(pair)
        assertEquals("binary", pair!!.first.readText())
        assertEquals("tokens", pair.second.readText())
        pair.second.delete()
        assertFalse(InternalModelExport.completeSenseVoiceFiles(exported) != null)
    }

}
