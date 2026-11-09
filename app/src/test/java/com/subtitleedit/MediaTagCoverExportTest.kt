package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaTagCoverExportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun onlyMarkedImagesBecomeCoversAndKeepTheOriginalImageExtension() {
        assertNull(MediaTagCoverExport.cover(MediaTagStream(0, "video"), "h264", null))
        assertNull(MediaTagCoverExport.cover(MediaTagStream(3, "attachment"), "ttf", "font.ttf"))
        assertEquals(MediaTagCover(2, false, "png"),
            MediaTagCoverExport.cover(MediaTagStream(2, "video", attachedPicture = true), "png", null))
        assertEquals(MediaTagCover(7, true, "jpg"),
            MediaTagCoverExport.cover(MediaTagStream(7, "attachment", coverAttachment = true), "mjpeg", "cover.jpg"))
        assertEquals(MediaTagCover(8, true, "webp"),
            MediaTagCoverExport.cover(MediaTagStream(8, "attachment", coverAttachment = true), null, "cover.WEBP"))
    }

    @Test fun commandMapsTheMarkedImageAndNeverEncodesOrRewritesTheInput() {
        val stream = MediaTagCoverExport.arguments("source song.mp3", "cover.png", MediaTagCover(4, false, "png"))
        assertTrue(stream.windowed(2).contains(listOf("-map", "0:4")))
        assertTrue(stream.windowed(2).contains(listOf("-c:v", "copy")))
        assertEquals("cover.png", stream.last())
        val attachment = MediaTagCoverExport.arguments("source.mkv", "cover.jpg", MediaTagCover(6, true, "jpg"))
        assertTrue(attachment.windowed(2).contains(listOf("-dump_attachment:6", "cover.jpg")))
        assertTrue(attachment.indexOf("-dump_attachment:6") < attachment.indexOf("-i"))
        assertFalse(attachment.contains("-dump_attachment:t"))
    }

    @Test fun exportingToTheSourceDirectoryNeverOverwritesExistingFiles() {
        val image = temporary.newFile("cover.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val alreadyExported = temporary.newFile("song_cover.png").apply { writeText("existing export") }
        val first = MediaTagCoverExport.copyUnique(image, temporary.root, "song_cover.png")
        val second = MediaTagCoverExport.copyUnique(image, temporary.root, "song_cover.png")
        assertEquals("song_cover (2).png", first.name)
        assertEquals("song_cover (3).png", second.name)
        assertEquals("existing export", alreadyExported.readText())
        assertArrayEquals(image.readBytes(), first.readBytes())
        assertArrayEquals(image.readBytes(), second.readBytes())
        val sameName = MediaTagCoverExport.copyUnique(image, temporary.root, image.name)
        assertEquals("cover (2).png", sameName.name)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), image.readBytes())
    }

    @Test fun emptySourcesAndInvalidNamesDoNotCreateExports() {
        val empty = temporary.newFile("empty.png")
        val image = temporary.newFile("valid.png").apply { writeBytes(byteArrayOf(1)) }
        for ((file, name) in listOf(empty to "export.png", image to "../outside.png")) {
            try {
                MediaTagCoverExport.copyUnique(file, temporary.root, name)
                fail("Invalid export was accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(setOf("empty.png", "valid.png"), temporary.root.list().orEmpty().toSet())
    }

    @Test fun realJpegAndPngCoversAreExportedByteForByteWithoutChangingMedia() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG to verify cover extraction", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("cover export ").toFile()
        fun run(arguments: List<String>): String {
            val log = File(root, "ffmpeg.log")
            val process = ProcessBuilder(listOf(executable!!, "-hide_banner", "-threads", "1", "-filter_threads", "1") + arguments)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("FFmpeg timed out") }
            val text = log.readText().replace("\r\n", "\n")
            assertEquals("$arguments\n$text", 0, process.exitValue())
            return text
        }
        try {
            for ((extension, codec) in listOf("jpg" to "mjpeg", "png" to "png")) {
                val cover = File(root, "cover.$extension")
                run(listOf("-y", "-f", "lavfi", "-i", "color=c=blue:size=64x64", "-frames:v", "1", "-threads", "1", cover.path))
                val media = File(root, "with-$extension.mp3")
                run(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-i", cover.path,
                    "-map", "0:a:0", "-map", "1:v:0", "-t", "0.4", "-c:a", "libmp3lame", "-c:v", "copy",
                    "-disposition:v:0", "attached_pic", media.path))
                val before = media.readBytes()
                val descriptor = requireNotNull(MediaTagCoverExport.cover(MediaTagStream(1, "video", attachedPicture = true), codec, null))
                val extracted = File(root, "export.$extension")
                run(MediaTagCoverExport.arguments(media.path, extracted.path, descriptor))
                assertArrayEquals(extension, cover.readBytes(), extracted.readBytes())
                assertArrayEquals("Source media changed", before, media.readBytes())
            }
            // Use an arbitrary image attachment codec, which remains an attachment instead of
            // being promoted to an attached-picture video stream by Matroska's demuxer.
            val attachment = File(root, "cover.webp").apply { writeBytes("RIFF\u0000\u0000\u0000\u0000WEBPoriginal bytes".toByteArray()) }
            val media = File(root, "attachment.mka")
            run(listOf("-y", "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.3", "-c:a", "flac",
                "-attach", attachment.path, "-metadata:s:t:0", "filename=cover.webp", "-metadata:s:t:0", "mimetype=image/webp", media.path))
            val before = media.readBytes()
            val exported = File(root, "export-attachment.webp")
            run(MediaTagCoverExport.arguments(media.path, exported.path, MediaTagCover(1, true, "webp")))
            assertArrayEquals(attachment.readBytes(), exported.readBytes())
            assertArrayEquals(before, media.readBytes())
        } finally { root.deleteRecursively() }
    }
}
