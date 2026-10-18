package com.subtitleedit.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaCacheCleanerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun statsAndClearCoverSelectedMediaKindsAndBothWaveformRoots() {
        val mediaDirectory = temporaryFolder.newFolder("media")
        val firstMedia = File(mediaDirectory, "first.mp4").apply { writeText("first video") }
        val secondMedia = File(mediaDirectory, "second.mp4").apply { writeText("second video") }
        val appCache = temporaryFolder.newFolder("app-cache")
        val firstKey = FileHashUtils.md5(firstMedia)
        val secondKey = FileHashUtils.md5(secondMedia)

        val appWaveform = cacheFile(appCache, "waveform/$firstKey/first.wave", "wave")
        val sourceWaveform = cacheFile(mediaDirectory, "$firstKey/first.a0.wave", "source wave")
        val partialWaveform = cacheFile(appCache, "waveform/$firstKey/first.wave.123.part", "partial")
        val secondWaveform = cacheFile(appCache, "waveform/$secondKey/second.wave", "other wave")
        val spectrogram = cacheFile(appCache, "waveform/$firstKey/first.a0.spec_0_800x300.png", "spec")
        val partialSpectrogram = cacheFile(appCache, "waveform/$firstKey/.spectrogram_part_123.png", "partial spec")
        val quickTranscribe = cacheFile(
            appCache,
            "quick_transcribe/$firstKey/quick_transcribe_default_16k.wav",
            "quick audio"
        )
        val partialQuickTranscribe = cacheFile(
            appCache,
            "quick_transcribe/$firstKey/.quick_transcribe_123.wav.part",
            "partial audio"
        )
        val secondQuickTranscribe = cacheFile(
            appCache,
            "quick_transcribe/$secondKey/quick_transcribe_0_16k.wav",
            "other audio"
        )
        val unrelatedCache = cacheFile(appCache, "waveform/unrelated/other.wave", "unrelated")
        val unrelatedSourceFile = cacheFile(mediaDirectory, "$firstKey/notes.txt", "keep")
        val unrelatedSourceWaveform = cacheFile(mediaDirectory, "$firstKey/other.wave", "keep")
        val emptyWaveform = cacheFile(appCache, "waveform/$firstKey/first.empty.wave", "")
        val missingMedia = File(mediaDirectory, "missing.mp4")

        val stats = MediaCacheCleaner.statsForMedia(
            listOf(firstMedia, secondMedia, firstMedia, missingMedia),
            appCache
        )

        assertEquals(5, stats.getValue(MediaCacheCleaner.Kind.WAVEFORM).fileCount)
        assertEquals(2, stats.getValue(MediaCacheCleaner.Kind.SPECTROGRAM).fileCount)
        assertEquals(3, stats.getValue(MediaCacheCleaner.Kind.QUICK_TRANSCRIBE).fileCount)
        assertEquals(0L, emptyWaveform.length())

        val waveformResult = MediaCacheCleaner.clearForMedia(
            listOf(firstMedia, firstMedia, missingMedia),
            listOf(MediaCacheCleaner.Kind.WAVEFORM),
            appCache
        )

        assertEquals(4, waveformResult.fileCount)
        assertFalse(appWaveform.exists())
        assertFalse(sourceWaveform.exists())
        assertFalse(partialWaveform.exists())
        assertFalse(emptyWaveform.exists())
        assertTrue(secondWaveform.exists())
        assertTrue(spectrogram.exists())
        assertTrue(partialSpectrogram.exists())
        assertTrue(quickTranscribe.exists())
        assertTrue(partialQuickTranscribe.exists())
        assertTrue(unrelatedCache.exists())
        assertTrue(unrelatedSourceFile.exists())
        assertTrue(unrelatedSourceWaveform.exists())

        val remaining = MediaCacheCleaner.clearForMedia(
            listOf(firstMedia, secondMedia),
            MediaCacheCleaner.Kind.values().asList(),
            appCache
        )

        assertEquals(6, remaining.fileCount)
        assertFalse(spectrogram.exists())
        assertFalse(partialSpectrogram.exists())
        assertFalse(quickTranscribe.exists())
        assertFalse(partialQuickTranscribe.exists())
        assertFalse(secondWaveform.exists())
        assertFalse(secondQuickTranscribe.exists())
        assertTrue(unrelatedCache.exists())
        assertTrue(unrelatedSourceFile.exists())
        assertTrue(unrelatedSourceWaveform.exists())
    }

    @Test
    fun zeroByteEntriesAreCountedAndMissingMediaIsIgnored() {
        val media = temporaryFolder.newFile("media.mkv").apply { writeText("video") }
        val appCache = temporaryFolder.newFolder("app-cache")
        val key = FileHashUtils.md5(media)
        val zeroByteCache = cacheFile(appCache, "waveform/$key/media.empty.wave", "")

        val stats = MediaCacheCleaner.statsForMedia(
            listOf(media, File(temporaryFolder.root, "missing.mkv")),
            appCache
        )
        val waveformStats = stats.getValue(MediaCacheCleaner.Kind.WAVEFORM)

        assertEquals(1, waveformStats.fileCount)
        assertEquals(0L, waveformStats.sizeBytes)
        assertTrue(zeroByteCache.exists())

        val result = MediaCacheCleaner.clearForMedia(
            listOf(media, File(temporaryFolder.root, "missing.mkv")),
            listOf(MediaCacheCleaner.Kind.WAVEFORM),
            appCache
        )

        assertEquals(1, result.fileCount)
        assertFalse(zeroByteCache.exists())
    }

    @Test
    fun snapshotCanClearCachesAfterTheMediaFileHasBeenDeleted() {
        val media = temporaryFolder.newFile("media.mp4").apply { writeText("video") }
        val appCache = temporaryFolder.newFolder("app-cache")
        val key = FileHashUtils.md5(media)
        val cache = cacheFile(appCache, "quick_transcribe/$key/quick_transcribe_default_16k.wav", "audio")
        val snapshot = MediaCacheCleaner.snapshotForMedia(
            listOf(media),
            listOf(MediaCacheCleaner.Kind.QUICK_TRANSCRIBE),
            appCache
        )

        assertTrue(media.delete())
        val result = MediaCacheCleaner.clear(snapshot)

        assertEquals(1, result.fileCount)
        assertFalse(cache.exists())
    }

    private fun cacheFile(root: File, relativePath: String, content: String): File =
        File(root, relativePath).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
}
