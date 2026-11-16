package com.subtitleedit.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSelectionShareTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "selection-share-${System.nanoTime()}").apply { check(mkdirs()) }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun singleAudioUsesAReadableContentUriAndReadOnlyGrant() {
        val file = media("one.mp3")
        val intent = mediaSelectionShareIntent(context, listOf(file.path))
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("audio/*", intent.type)
        val uri = requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.provider", uri.authority)
        assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }

    @Test fun multipleFilesKeepSelectionOrderAndAllUriGrantsWithTheCommonMimeType() {
        val audio = media("one.mp3")
        val otherAudio = media("two.m4a")
        val video = media("three.mp4")
        val audioIntent = mediaSelectionShareIntent(context, listOf(audio.path, otherAudio.path))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, audioIntent.action)
        assertEquals("audio/*", audioIntent.type)
        assertEquals("video/*", mediaSelectionShareIntent(context, listOf(video.path)).type)
        val mixed = mediaSelectionShareIntent(context, listOf(video.path, audio.path))
        assertEquals("*/*", mixed.type)
        val uris = requireNotNull(mixed.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(2, uris.size)
        assertEquals(uris, (0 until mixed.clipData!!.itemCount).map { mixed.clipData!!.getItemAt(it).uri })
        listOf(video, audio).zip(uris).forEach { (file, uri) ->
            assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    private fun media(name: String) = File(root, name).apply { writeText(name) }
}
