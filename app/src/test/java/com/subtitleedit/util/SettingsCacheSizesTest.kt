package com.subtitleedit.util

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsCacheSizesTest {
    private class Entry(path: String, private val bytes: Long) : File(path) {
        var sizeReads = 0
        override fun isDirectory() = false
        override fun isFile() = true
        override fun length(): Long { sizeReads++; return bytes }
    }

    private class Directory(path: String, private vararg val children: File) : File(path) {
        var listings = 0
        override fun isDirectory() = true
        override fun isFile() = false
        override fun listFiles(): Array<File> { listings++; return children.toList().toTypedArray() }
    }

    @Test fun `one traversal counts only the existing cache categories and reads sizes once`() {
        val root = File("cache").absoluteFile
        val waveformRoot = File(root, "waveform")
        val wave = Entry(File(waveformRoot, "sound.wave").path, 11)
        val spec = Entry(File(waveformRoot, "sound.spec_1.png").path, 13)
        val ordinaryPng = Entry(File(waveformRoot, "cover.png").path, 101)
        val nestedQuick = Entry(File(waveformRoot, "quick_transcribe_nested_16k.wav").path, 17)
        val ignoredWave = Entry(File(root, "outside.wave").path, 103)
        val quick = Entry(File(root, "quick_transcribe_root_16k.wav").path, 19)
        val ignoredWav = Entry(File(root, "quick_transcribe_other.wav").path, 107)
        val waveform = Directory(waveformRoot.path, wave, spec, ordinaryPng, nestedQuick)
        val cache = Directory(root.path, waveform, ignoredWave, quick, ignoredWav)

        assertEquals(SettingsCacheSizes(11L, 13L, 36L), readSettingsCacheSizes(cache))
        assertEquals(1, cache.listings)
        assertEquals(1, waveform.listings)
        listOf(wave, spec, nestedQuick, quick).forEach { assertEquals(1, it.sizeReads) }
        listOf(ordinaryPng, ignoredWave, ignoredWav).forEach { assertEquals(0, it.sizeReads) }
    }

    @Test fun `a cancelled settings page stops traversing cache files`() {
        val root = File("cache").absoluteFile
        val first = Entry(File(root, "quick_transcribe_first_16k.wav").path, 11)
        val second = Entry(File(root, "quick_transcribe_second_16k.wav").path, 13)
        val cache = Directory(root.path, first, second)
        var visited = 0
        assertThrows(CancellationException::class.java) {
            readSettingsCacheSizes(cache) {
                if (++visited == 3) throw CancellationException("Settings page left")
            }
        }
        assertEquals(1, first.sizeReads)
        assertEquals(0, second.sizeReads)
    }
}
