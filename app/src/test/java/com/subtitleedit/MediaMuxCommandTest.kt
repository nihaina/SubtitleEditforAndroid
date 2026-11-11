package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaMuxCommandTest {
    private val source = MediaMuxProbe("mov,mp4,m4a,3gp,3g2,mj2", listOf(
        MediaMuxStream(0, "video", "h264"), MediaMuxStream(1, "audio", "aac"),
        MediaMuxStream(2, "subtitle", "mov_text"), MediaMuxStream(3, "video", "mjpeg", picture = true)
    ), 1.0)
    private val external = MediaMuxProbe("mp3", listOf(MediaMuxStream(0, "audio", "mp3")), 1.0)
    private fun maps(arguments: List<String>) = arguments.windowed(2).filter { it[0] == "-map" }.map { it[1] }

    @Test fun mergeReplacesOrAddsTracksWithoutEncodingOrTreatingCoverAsVideo() {
        val replace = MediaMuxCommand.merge("video.mp4", "audio.mp3", "out", "mp4", source, external, MediaMuxAudioAction.REPLACE)
        val add = MediaMuxCommand.merge("video.mp4", "audio.mp3", "out", "mp4", source, external, MediaMuxAudioAction.ADD)
        assertEquals(listOf("0:0", "1:0"), maps(replace))
        assertEquals(listOf("0:0", "0:1", "1:0"), maps(add))
        assertTrue(replace.windowed(2).contains(listOf("-c", "copy")))
        assertFalse(replace.contains("-shortest"))
        assertTrue(replace.windowed(2).contains(listOf("-map_chapters", "0")))
    }

    @Test fun separationProducesIndependentVideoOnlyAndAudioOnlyStreams() {
        val video = MediaMuxCommand.splitVideo("input", "output", "mp4", source)
        val audio = MediaMuxCommand.splitAudio("input", "output", "ipod", source)
        assertEquals(listOf("0:0"), maps(video))
        assertEquals(listOf("0:1"), maps(audio))
        assertTrue("-an" in video)
        assertTrue("-vn" in audio)
        assertTrue(video.windowed(2).contains(listOf("-c", "copy")))
        assertTrue(audio.windowed(2).contains(listOf("-c", "copy")))
    }

    @Test fun extractedAudioSelectsACopyCompatibleContainerAndMultitrackUsesMatroska() {
        assertEquals(MediaMuxAudioFormat("m4a", "ipod"), MediaMuxCommand.audioFormat(listOf("aac")))
        assertEquals(MediaMuxAudioFormat("wav", "wav"), MediaMuxCommand.audioFormat(listOf("pcm_s24le")))
        assertEquals(MediaMuxAudioFormat("mka", "matroska"), MediaMuxCommand.audioFormat(listOf("pcm_s16be")))
        assertEquals(MediaMuxAudioFormat("mka", "matroska"), MediaMuxCommand.audioFormat(listOf("pcm_s24be")))
        assertEquals(MediaMuxAudioFormat("mka", "matroska"), MediaMuxCommand.audioFormat(listOf("pcm_s8")))
        assertEquals(MediaMuxAudioFormat("mp3", "mp3"), MediaMuxCommand.audioFormat(listOf("mp3")))
        assertEquals(MediaMuxAudioFormat("mka", "matroska"), MediaMuxCommand.audioFormat(listOf("aac", "aac")))
        assertEquals(MediaMuxAudioFormat("mka", "matroska"), MediaMuxCommand.audioFormat(listOf("unknown")))
    }

    @Test fun originalVideoExtensionDeterminesTheContainerDespiteAmbiguousProbeNames() {
        assertEquals("mp4", MediaMuxCommand.videoMuxer("test.mp4", source.format))
        assertEquals("mpegts", MediaMuxCommand.videoMuxer("test.M2TS", "mpegts"))
        assertEquals("matroska", MediaMuxCommand.videoMuxer("test.mkv", "matroska,webm"))
        assertEquals("webm", MediaMuxCommand.videoMuxer("test.webm", "matroska,webm"))
        assertEquals("test.mp4（1）", MediaMuxOutputStore.folderName("test.mp4", 1))
        assertEquals("test.mp4", MediaMuxOutputStore.folderName("test.mp4", 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun separationWithoutAnyAudioFailsBeforeCreatingOutputs() {
        MediaMuxCommand.audioFormat(emptyList())
    }
}
