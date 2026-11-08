package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional real muxing check, using the same command builder as Android. */
class MediaConvertMetadataFfmpegTest {
    @Test
    fun selectedArtworkAndEditedTagsSurviveAudioAndVideoMuxing() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG to a host FFmpeg executable", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("media metadata ").toFile()
        fun run(arguments: List<String>): String {
            val log = File(root, "ffmpeg.log")
            val process = ProcessBuilder(listOf(executable!!, "-hide_banner", "-threads", "1", "-filter_threads", "1") + arguments)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("FFmpeg timed out: $arguments")
            }
            val output = log.readText()
            assertEquals("$arguments\n$output", 0, process.exitValue())
            return output
        }
        fun tags(file: File): String {
            val text = File(root, "tags.txt")
            run(listOf("-y", "-i", file.path, "-map_metadata", "0", "-f", "ffmetadata", text.path))
            return text.readText().lowercase()
        }
        try {
            val audio = File(root, "source.wav")
            val video = File(root, "source.mkv")
            val cover = File(root, "cover image.jpg")
            run(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.6", "-metadata", "album=Original Album", audio.path))
            run(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=160x128:rate=25", "-i", audio.path, "-t", "0.6", "-c:v", "ffv1", "-c:a", "pcm_s16le", video.path))
            run(listOf("-y", "-f", "lavfi", "-i", "color=c=blue:size=64x64", "-frames:v", "1", "-threads", "1", cover.path))
            val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
            val audioMetadata = MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = cover.path)
                .withValue(MediaConvertMetadataField.TITLE, "Edited title")
                .withValue(MediaConvertMetadataField.ARTIST, "Singer")
                .withValue(MediaConvertMetadataField.GENRE, "Pop")
                .withValue(MediaConvertMetadataField.YEAR, "2026")
            for (extension in listOf("mp3", "m4a", "flac")) {
                val output = File(root, "audio.$extension")
                run(MediaConvertCommand.build(audio.path, output.path, formats.first { it.extension == extension },
                    MediaConvertOptions(), MediaConvertSource(false, 0.6), metadata = audioMetadata))
                val writtenTags = tags(output)
                assertTrue(extension, "title=edited title" in writtenTags)
                assertTrue(extension, "artist=singer" in writtenTags)
                assertTrue("Untouched source album must survive $extension", "album=original album" in writtenTags)
                val log = run(listOf("-i", output.path, "-map", "0:a:0", "-f", "null", "-"))
                assertTrue("Missing artwork in $extension\n$log", "attached pic" in log)
            }
            for (extension in listOf("mp4", "mkv")) {
                val output = File(root, "video.$extension")
                val metadata = MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = cover.path)
                    .withValue(MediaConvertMetadataField.TITLE, "Film")
                    .withValue(MediaConvertMetadataField.AUTHOR, "Director")
                run(MediaConvertCommand.build(video.path, output.path, formats.first { it.extension == extension },
                    MediaConvertOptions(selectedVideoCodec = "libx264", selectedQualityId = "23",
                        videoSettings = MediaConvertVideoSettings(flipHorizontal = true)),
                    MediaConvertSource(true, 0.6, 25.0, videoWidth = 160, videoHeight = 128), metadata = metadata))
                val writtenTags = tags(output)
                assertTrue(extension, "title=film" in writtenTags)
                val log = run(listOf("-i", output.path, "-map", "0:a:0", "-f", "null", "-"))
                if (extension == "mp4") assertTrue("Missing MP4 attached picture\n$log", "attached pic" in log)
                else assertTrue("Missing Matroska attachment\n$log", "cover.jpg" in log)
            }
            val mp3 = File(root, "audio.mp3")
            for (extension in listOf("mp3", "m4a", "flac")) {
                val preserved = File(root, "preserved.$extension")
                run(MediaConvertCommand.build(mp3.path, preserved.path, formats.first { it.extension == extension },
                    MediaConvertOptions(), MediaConvertSource(false, 0.6, audioCodecName = "mp3", attachedPictureStreamIndex = 1),
                    metadata = MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "Batch title")))
                assertTrue("Default artwork must survive $extension", "attached pic" in run(listOf("-i", preserved.path, "-map", "0:a:0", "-f", "null", "-")))
                assertTrue("Untouched album must survive $extension", "album=original album" in tags(preserved))
            }
            val twoPassOutput = File(root, "two pass.mp4")
            val twoPassMetadata = MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = cover.path)
                .withValue(MediaConvertMetadataField.TITLE, "Two pass film")
            val twoPassOptions = MediaConvertOptions(
                selectedVideoCodec = "libx264", videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "1000"),
                videoSettings = MediaConvertVideoSettings(twoPass = true, flipHorizontal = true))
            val plan = MediaConvertCommand.plan(video.path, twoPassOutput.path, formats.first { it.extension == "mp4" },
                twoPassOptions, MediaConvertSource(true, 0.6, 25.0, videoWidth = 160, videoHeight = 128),
                File(root, "pass stats").path, metadata = twoPassMetadata)
            plan.forEach { command -> run(command) }
            assertTrue("title=two pass film" in tags(twoPassOutput))
            assertTrue("attached pic" in run(listOf("-i", twoPassOutput.path, "-map", "0:a:0", "-f", "null", "-")))
            val cleared = File(root, "no-cover.mp3")
            val clearMetadata = MediaConvertMetadata(cover = MediaConvertCoverMode.NONE)
                .withValue(MediaConvertMetadataField.TITLE, "")
            run(MediaConvertCommand.build(mp3.path, cleared.path, formats.first { it.extension == "mp3" },
                MediaConvertOptions(selectedAudioCodec = "copy"), MediaConvertSource(false, 0.6, attachedPictureStreamIndex = 1),
                metadata = clearMetadata))
            assertFalse("title=" in tags(cleared))
            assertFalse("attached pic" in run(listOf("-i", cleared.path, "-map", "0:a:0", "-f", "null", "-")))
        } finally {
            root.deleteRecursively()
        }
    }
}
