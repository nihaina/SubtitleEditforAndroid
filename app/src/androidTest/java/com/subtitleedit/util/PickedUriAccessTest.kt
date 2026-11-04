package com.subtitleedit.util

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Local picker results must work without a document provider or persisted URI grant. */
@RunWith(AndroidJUnit4::class)
class PickedUriAccessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun localFileMetadataAndReadAccessDoNotQueryADocumentProvider() = withDirectory { directory ->
        val file = File(directory, "字幕 #1.srt").apply { writeText("subtitle content") }
        val uri = Uri.fromFile(file)
        PickedUriAccess.retainPermission(context, uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        assertEquals(file, PickedUriAccess.file(uri))
        assertEquals(file.name, UriDisplayName.of(context, uri))
        assertEquals(file.name, PickedUriAccess.document(context, uri)!!.name)
        assertEquals("subtitle content", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
    }

    @Test fun localDirectorySupportsExistingTokenizerAndLogDocumentOperations() = withDirectory { directory ->
        val source = File(directory, "config.json").apply { writeText("{}") }
        val selected = PickedUriAccess.directory(context, Uri.fromFile(directory))!!
        assertTrue(selected.isDirectory)
        assertEquals(setOf(source.name), selected.listFiles().map { it.name }.toSet())
        val output = PickedUriAccess.createFile(selected, "text/plain", "log.txt")!!
        context.contentResolver.openOutputStream(output.uri, "wt")!!.bufferedWriter().use { it.write("log data") }
        assertEquals("log data", File(directory, "log.txt").readText())
        assertNull(PickedUriAccess.createFile(selected, "text/plain", "log.txt"))
        assertEquals(source.canonicalFile,
            Qwen3ForcedAlignerPathResolver.resolve(context, Uri.fromFile(source))!!.canonicalFile)
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = File(context.cacheDir, "local_picker_uri_${System.nanoTime()}").apply { mkdirs() }
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
