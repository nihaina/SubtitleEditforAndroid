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
            val audioLyrics = format.isAudioOnly && MediaConvertAudioLyrics.maxFiles(format) > 0
            assertEquals(format.extension, format.extension in soft || audioLyrics, mux.isNotEmpty())
            if (mux.isNotEmpty()) assertEquals(setOf("srt", "ass", "ssa", "vtt", "lrc") + if (audioLyrics) setOf("txt") else emptySet(), mux)
        }
        assertTrue(MediaConvertSubtitles.allowedExtensions(format("mp4"), MediaConvertSubtitleType.BURN_IN, "copy").isEmpty())
        assertTrue(MediaConvertSubtitles.allowedExtensions(format("mp4"), MediaConvertSubtitleType.BURN_IN, "auto", false).isEmpty())
        assertEquals("ass", MediaConvertSubtitles.extension("字幕.ASS"))
    }

    @Test fun multipleSoftTracksHaveIndependentEncodersAndDoNotMoveTheCoverInput() {
        val subtitles = listOf(subtitle(MediaConvertSubtitleType.SOFT), subtitle(MediaConvertSubtitleType.SOFT, "第二轨.ass"))
        val args = MediaConvertCommand.build("in", "out.mkv", format("mkv"), MediaConvertOptions(selectedVideoCodec = "copy"), source,
            metadata = MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = "cover.jpg"), subtitles = subtitles)
        assertTrue(args.windowed(2).contains(listOf("-map", "1:s:0")))
        assertTrue(args.windowed(2).contains(listOf("-map", "2:s:0")))
        assertEquals("srt", args.value("-c:s:0"))
        assertEquals("ass", args.value("-c:s:1"))
        assertEquals("default", args.value("-disposition:s:0"))
        assertEquals("0", args.value("-disposition:s:1"))
        val withStreamCover = MediaConvertCommand.build("in", "out.mp4", format("mp4"), MediaConvertOptions(), source,
            metadata = MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = "cover.jpg"), subtitles = subtitles)
        assertTrue(withStreamCover.windowed(2).contains(listOf("-map", "3:v:0")))
        val synthetic = MediaConvertCommand.build("in", "out.mp4", format("mp4"), MediaConvertOptions(), MediaConvertSource(false, 2.0),
            metadata = MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = "cover.jpg"), subtitles = subtitles)
        assertTrue(synthetic.windowed(2).contains(listOf("-map", "2:s:0")))
        assertTrue(synthetic.windowed(2).contains(listOf("-map", "3:s:0")))
        assertTrue(synthetic.windowed(2).contains(listOf("-map", "4:v:0")))
    }

    @Test fun existingSourceTracksArePreservedAndAdaptedAlongsideNewSubtitles() {
        val tracks = listOf(MediaConvertSourceSubtitle(4, "ass", "eng", "Original", true),
            MediaConvertSourceSubtitle(5, "hdmv_pgs_subtitle", "jpn", "Bitmap"))
        val withTracks = source.copy(subtitleStreams = tracks)
        val args = MediaConvertCommand.build("in", "out.mkv", format("mkv"), MediaConvertOptions(), withTracks,
            subtitles = listOf(subtitle(MediaConvertSubtitleType.SOFT)))
        assertTrue(args.windowed(2).contains(listOf("-map", "0:4")))
        assertTrue(args.windowed(2).contains(listOf("-map", "0:5")))
        assertEquals("copy", args.value("-c:s:0"))
        assertEquals("copy", args.value("-c:s:1"))
        assertEquals("srt", args.value("-c:s:2"))
        assertTrue(args.windowed(2).contains(listOf("-metadata:s:s:0", "language=eng")))
        assertTrue(args.windowed(2).contains(listOf("-metadata:s:s:0", "title=Original")))
        val mp4 = MediaConvertCommand.build("in", "out.mp4", format("mp4"), MediaConvertOptions(), withTracks)
        assertTrue(mp4.windowed(2).contains(listOf("-map", "0:4")))
        assertFalse(mp4.windowed(2).contains(listOf("-map", "0:5")))
        assertEquals("mov_text", mp4.value("-c:s:0"))
        assertNull(MediaConvertSubtitles.sourceEncoder(format("mp3"), "subrip"))
    }

    @Test fun audioContainersUseNativeLyricTagsAndMkaUsesTimedTracks() {
        val lyrics = listOf(subtitle(MediaConvertSubtitleType.SOFT, "first.lrc"), subtitle(MediaConvertSubtitleType.SOFT, "second.srt"))
        for (extension in listOf("mka")) {
            val args = MediaConvertCommand.build("in", "out.$extension", format(extension), MediaConvertOptions(), source, subtitles = lyrics)
            assertTrue(extension, args.windowed(2).contains(listOf("-map", "1:s:0")))
            assertTrue(extension, args.windowed(2).contains(listOf("-map", "2:s:0")))
            assertFalse(extension, "-sn" in args)
            assertTrue(extension, MediaConvertSubtitles.allowedExtensions(format(extension), MediaConvertSubtitleType.BURN_IN, "").isEmpty())
        }
        for (extension in listOf("mp3", "aac", "flac", "wav", "ogg", "opus")) {
            assertTrue(extension, MediaConvertSubtitles.allowedExtensions(format(extension), MediaConvertSubtitleType.SOFT, "").isNotEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                MediaConvertCommand.build("in", "out.$extension", format(extension), MediaConvertOptions(), source, subtitles = lyrics)
            }
        }
        assertTrue(MediaConvertSubtitles.allowedExtensions(format("ac3"), MediaConvertSubtitleType.SOFT, "").isEmpty())
        val lyric = MediaConvertLyric("Lyrics", "[00:00.00]Song line\n")
        for (extension in listOf("mp3", "aac", "flac", "wav", "ogg", "opus", "m4a", "m4b", "wma", "wv")) {
            val args = MediaConvertCommand.build("in", "out.$extension", format(extension), MediaConvertOptions(), source, lyrics = listOf(lyric))
            assertFalse(extension, args.windowed(2).any { it == listOf("-map", "1:s:0") })
        }
    }

    @Test fun aacStoresMultipleLyricVersionsInId3WithoutChangingItsAdtsContainer() {
        val format = format("aac")
        assertEquals("adts", format.formatName)
        assertEquals(setOf("srt", "ass", "ssa", "vtt", "lrc", "txt"),
            MediaConvertSubtitles.allowedExtensions(format, MediaConvertSubtitleType.SOFT, ""))
        val lyrics = listOf(MediaConvertLyric("Chinese", "[00:00.00]海风\n"),
            MediaConvertLyric("English", "[00:00.00]Sea breeze\n"))
        val args = MediaConvertCommand.build("in", "out.aac", format, MediaConvertOptions(), source, lyrics = lyrics)
        assertEquals("1", args.value("-write_id3v2"))
        assertEquals("adts", args.value("-f"))
        assertTrue("-sn" in args)
        assertFalse(args.any { it.startsWith("-c:s") })
        assertFalse(args.windowed(2).any { it == listOf("-map", "1:s:0") })
    }

    @Test fun videoBurnInRejectsMultipleAndMixedModeSubtitles() {
        val burn = subtitle(MediaConvertSubtitleType.BURN_IN)
        for (subtitles in listOf(listOf(burn, burn.copy(fileName = "other.srt")), listOf(burn, subtitle(MediaConvertSubtitleType.SOFT)))) {
            assertThrows(IllegalArgumentException::class.java) {
                MediaConvertCommand.build("in", "out.mp4", format("mp4"), MediaConvertOptions(), source, subtitles = subtitles)
            }
        }
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
        assertEquals(MediaConvertVideoSettingsSupport.filters(options.resolution, options.videoSettings, source)!! + "," + MediaConvertSubtitles.burnFilter(sub), args.value("-vf"))
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
