package com.subtitleedit.util

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DraftManagerFileNameTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun duplicateSubtitleNamesUseOutputWriterSuffixConvention() {
        val directory = temporaryFolder.root

        assertEquals("captions.srt", DraftManager.uniqueDraftFileName(directory, "captions.srt"))
        File(directory, "captions.srt").writeText("first")

        assertEquals("captions (1).srt", DraftManager.uniqueDraftFileName(directory, "captions.srt"))
        File(directory, "captions (1).srt").writeText("second")

        assertEquals("captions (2).srt", DraftManager.uniqueDraftFileName(directory, "captions.srt"))
    }

    @Test
    fun duplicateExtensionlessNamesAppendNumberToStem() {
        val directory = temporaryFolder.root
        File(directory, "captions").writeText("first")

        assertEquals("captions (1)", DraftManager.uniqueDraftFileName(directory, "captions"))
    }

    @Test
    fun subtitlesAreStoredInsideAudioFolderWithoutChangingOlderDrafts() {
        val root = temporaryFolder.root
        val audioFolder = File(root, "录音.wav").apply { mkdirs() }
        val legacy = File(audioFolder, "01_录音.wav").apply { writeText("older draft") }
        val content = "1\r\n00:00:01,000 --> 00:00:02,000\r\n字幕\r\n"

        assertEquals("录音.srt", DraftManager.saveDraft(root, "录音.wav", "录音.srt", content))
        assertEquals("录音 (1).srt", DraftManager.saveDraft(root, "录音.wav", "录音.srt", "second"))
        assertEquals(content, File(audioFolder, "录音.srt").readText())
        assertEquals("second", File(audioFolder, "录音 (1).srt").readText())
        assertEquals("older draft", legacy.readText())
    }

    @Test
    fun simultaneousSavesKeepEveryDraft() {
        val root = temporaryFolder.root
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((1..12).map { index ->
                Callable { DraftManager.saveDraft(root, "audio.wav", "subtitle.vtt", "draft $index") to index }
            }).map { it.get() }
            assertEquals(12, results.map { it.first }.toSet().size)
            results.forEach { (name, index) ->
                assertEquals("draft $index", File(File(root, "audio.wav"), name).readText())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun emptyDraftFoldersAreRemovedWhenListingOrDeletingLastFile() {
        val root = temporaryFolder.root
        val stale = File(root, "stale.wav").apply { mkdirs() }
        val active = File(root, "active.wav").apply { mkdirs() }
        File(active, "captions.srt").writeText("draft")

        DraftManager.removeEmptyDraftFolders(root)

        assertEquals(false, stale.exists())
        assertEquals(true, active.exists())
        File(active, "captions.srt").delete()
        DraftManager.removeEmptyDraftFolders(root)
        assertEquals(false, active.exists())
    }
}
