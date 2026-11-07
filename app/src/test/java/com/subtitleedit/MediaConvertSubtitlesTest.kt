package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertSubtitlesTest {
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
    private fun format(extension: String) = formats.first { it.extension == extension }
    private val source = MediaConvertSource(true, 2.0, 25.0)
    private fun subtitle(type: MediaConvertSubtitleType, name: String = "captions.srt") =
        MediaConvertSubtitleInput("/task cache/$name", name, type)
    private fun List<String>.value(flag: String) = indexOf(flag).takeIf { it >= 0 }?.let { get(it + 1) }

    @Test fun extensionChoicesFollowVideoContainerModeAndEncoder() {
        val soft = setOf("mp4", "mkv", "mov", "m4v", "3gp", "3g2", "webm")
        for (format in formats) {
            val burn = MediaConvertSubtitles.allowedExtensions(format, MediaConvertSubtitleType.BURN_IN, "auto")
            assertEquals(format.extension, !format.isAudioOnly, burn.isNotEmpty())
            val mux = MediaConvertSubtitles.allowedExtensions(format, MediaConvertSubtitleType.SOFT, "copy")
            assertEquals(format.extension, format.extension in soft, mux.isNotEmpty())
            if (mux.isNotEmpty()) assertEquals(setOf("srt", "ass", "ssa", "vtt"), mux)
        }
        assertTrue(MediaConvertSubtitles.allowedExtensions(format("mp4"), MediaConvertSubtitleType.BURN_IN, "copy").isEmpty())
        assertTrue(MediaConvertSubtitles.allowedExtensions(format("mp4"), MediaConvertSubtitleType.BURN_IN, "auto", false).isEmpty())
        assertEquals("ass", MediaConvertSubtitles.extension("字幕.ASS"))
    }

    @Test fun softSubtitlesMapOnlyTheAddedTrackAndKeepCopyVideoAvailable() {
        val args = MediaConvertCommand.build("in", "out.mkv", format("mkv"), MediaConvertOptions(selectedVideoCodec = "copy"), source,
            subtitle = subtitle(MediaConvertSubtitleType.SOFT, "captions.ass"))
        assertEquals("copy", args.value("-c:v"))
        assertEquals("ass", args.value("-c:s"))
        assertFalse("-sn" in args)
        assertFalse("-vf" in args)
        assertTrue(args.windowed(2).any { it == listOf("-map", "1:s:0") })
        assertEquals("default", args.value("-disposition:s:0"))
        val synthetic = MediaConvertCommand.build("in", "out.mp4", format("mp4"), MediaConvertOptions(), MediaConvertSource(false, 2.0),
            subtitle = subtitle(MediaConvertSubtitleType.SOFT))
        assertTrue(synthetic.windowed(2).any { it == listOf("-map", "2:s:0") })
        assertEquals("mov_text", synthetic.value("-c:s"))
    }

    @Test fun burnIsAppliedAfterVideoTransformsAndStillDropsSubtitleTracks() {
        val options = MediaConvertOptions(videoSettings = MediaConvertVideoSettings(rotation = MediaConvertRotation.CLOCKWISE))
        val sub = subtitle(MediaConvertSubtitleType.BURN_IN)
        val args = MediaConvertCommand.build("in", "out.mp4", format("mp4"), options, source, subtitle = sub)
        assertTrue("-sn" in args)
        assertNull(args.value("-c:s"))
        assertEquals(MediaConvertVideoSettingsSupport.filters(options.resolution, options.videoSettings) + "," + MediaConvertSubtitles.burnFilter(sub), args.value("-vf"))
        assertThrows(IllegalArgumentException::class.java) {
            MediaConvertCommand.build("in", "out", format("mp4"), options.copy(selectedVideoCodec = "copy"), source, subtitle = sub)
        }
    }

    @Test fun twoPassBurnsBothPassesAndMuxesOnlyInTheFinalPass() {
        val options = MediaConvertOptions(videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "500"),
            videoSettings = MediaConvertVideoSettings(twoPass = true))
        for (type in MediaConvertSubtitleType.entries) {
            val commands = MediaConvertCommand.plan("in", "out", format("mp4"), options, source, "/tmp/stats", subtitle = subtitle(type))
            assertEquals(2, commands.size)
            if (type == MediaConvertSubtitleType.BURN_IN) {
                assertEquals(commands[0].value("-vf"), commands[1].value("-vf"))
                assertTrue(commands[0].value("-vf")!!.contains("subtitles="))
            } else {
                assertNull(commands[0].value("-c:s"))
                assertTrue("-sn" in commands[0])
                assertEquals("mov_text", commands[1].value("-c:s"))
            }
        }
    }

    @Test fun incompatibleFormatsAndUntimedOrPairedFilesAreRejected() {
        for ((output, name) in listOf("mp3" to "caption.srt", "avi" to "caption.srt", "f4v" to "caption.srt", "mp4" to "caption.txt", "mkv" to "caption.idx", "mkv" to "caption.sub")) {
            assertThrows("$output/$name", IllegalArgumentException::class.java) {
                MediaConvertCommand.build("in", "out", format(output), MediaConvertOptions(), source, subtitle = subtitle(MediaConvertSubtitleType.SOFT, name))
            }
        }
        assertTrue(MediaConvertSubtitles.hasBurnInFilter(" .. subtitles         V->V       Render text subtitles"))
        assertFalse(MediaConvertSubtitles.hasBurnInFilter(" .. ass               V->V       Render ASS subtitles"))
    }
}
