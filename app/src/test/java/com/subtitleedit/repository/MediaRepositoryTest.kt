package com.subtitleedit.repository

import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import com.subtitleedit.nativebridge.MediaProbeResult
import com.subtitleedit.nativebridge.NativeMediaEngine
import com.subtitleedit.nativebridge.NativeMediaOperation
import com.subtitleedit.nativebridge.PcmFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cacheKeyRemainsCompatibleWithExistingSmallFileMd5() = runBlocking {
        val source = temporaryFolder.newFile("audio.wav").apply { writeText("abc") }
        val repository: MediaRepository = DefaultMediaRepository(temporaryFolder.newFolder("cache"))

        assertEquals("900150983cd24fb0d6963f7d28e17f72", repository.getCacheKey(source))
        assertEquals("abc", source.readText())
    }

    @Test
    fun cacheKeyDependsOnContentRatherThanFilePath() = runBlocking {
        val first = temporaryFolder.newFile("first.wav").apply { writeText("same audio") }
        val second = temporaryFolder.newFile("second.wav").apply { writeBytes(first.readBytes()) }
        val repository: MediaRepository = DefaultMediaRepository(temporaryFolder.newFolder("cache"))
        val firstKey = repository.getCacheKey(first)

        assertEquals(firstKey, repository.getCacheKey(second))
        second.appendText("changed")
        assertNotEquals(firstKey, repository.getCacheKey(second))
    }

    @Test
    fun largeFileCacheKeyKeepsExistingHeadAndTailSampling() = runBlocking {
        val source = temporaryFolder.newFile("large.wav")
        val sectionSize = 1024L * 1024L
        RandomAccessFile(source, "rw").use { it.setLength(sectionSize * 3) }
        val repository: MediaRepository = DefaultMediaRepository(temporaryFolder.newFolder("cache"))
        val originalKey = repository.getCacheKey(source)

        RandomAccessFile(source, "rw").use {
            it.seek(sectionSize + 1)
            it.write(1)
        }
        assertEquals(originalKey, repository.getCacheKey(source))
        RandomAccessFile(source, "rw").use {
            it.seek(source.length() - 1)
            it.write(1)
        }
        assertNotEquals(originalKey, repository.getCacheKey(source))
    }

    @Test
    fun missingFileDoesNotProduceAReusableCacheKey() {
        val repository: MediaRepository = DefaultMediaRepository(temporaryFolder.newFolder("cache"))

        assertThrows(FileNotFoundException::class.java) {
            runBlocking { repository.getCacheKey(File(temporaryFolder.root, "missing.wav")) }
        }
    }

    @Test
    fun releasingUnusedRepositoryPreservesOriginalFilesAndUnrelatedCaches() {
        val source = temporaryFolder.newFile("original.wav").apply { writeText("original") }
        val cacheDir = temporaryFolder.newFolder("cache")
        val unrelated = File(cacheDir, "other.wav").apply { writeText("other session") }
        val repository: MediaRepository = DefaultMediaRepository(cacheDir)

        repository.release()
        repository.release()

        assertEquals("original", source.readText())
        assertEquals("other session", unrelated.readText())
    }

    @Test
    fun audioPreparationUsesInjectedNativeEngineForVideoProbe() = runBlocking {
        val source = temporaryFolder.newFile("offset.mp3")
        val engine = RecordingNativeMediaEngine()
        val repository = DefaultMediaRepository(
            cacheDir = temporaryFolder.newFolder("cache"),
            nativeMediaEngine = engine
        )

        val prepared = repository.prepareAudio(source, inspectVideoAudioTrack = true)

        assertTrue(engine.probed)
        assertFalse(engine.converted)
        assertFalse(prepared.wasFixed)
        assertEquals(2, prepared.audioStreamIndex)
        assertEquals(source, prepared.playbackFile)
        repository.release()
    }

    private class RecordingNativeMediaEngine : NativeMediaEngine {
        var probed = false
        var converted = false

        override fun probe(file: File, inspectVideoAudioTrack: Boolean): MediaProbeResult {
            probed = true
            return MediaProbeResult(startTimeSeconds = 1.0, defaultAudioStreamIndex = 2)
        }

        override fun openOperation(): NativeMediaOperation = object : NativeMediaOperation {
            override suspend fun convertToWav(inputFile: File, outputFile: File): Boolean {
                converted = true
                outputFile.writeBytes(ByteArray(45))
                return true
            }

            override suspend fun convertToPcm(inputFile: File, outputFile: File, format: PcmFormat): Boolean = false

            override fun cancel() = Unit
        }
    }
}
