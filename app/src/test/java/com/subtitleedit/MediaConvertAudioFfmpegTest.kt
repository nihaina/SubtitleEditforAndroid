package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaConvertAudioFfmpegTest {
    @Test
    fun offeredVideoAudioPairsEncodeProbeAndDecode() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG for audio/container checks", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("audio container matrix ").toFile()
        fun run(command: List<String>, name: String): Pair<Int, String> {
            val log = File(root, "$name.log")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("Timeout: $command") }
            return process.exitValue() to log.readText()
        }
        fun ffmpeg(args: List<String>) = run(listOf(executable!!, "-loglevel", "error", "-filter_threads", "1", "-threads", "1") + args, "ffmpeg")
        val codecIds = mapOf("libopencore_amrnb" to "amr_nb", "libvo_amrwbenc" to "amr_wb", "dca" to "dts", "libmp3lame" to "mp3", "libopus" to "opus", "libspeex" to "speex", "real_144" to "ra_144", "libvorbis" to "vorbis")
        try {
            val encoders = ffmpeg(listOf("-hide_banner", "-loglevel", "info", "-encoders")).second
            val formats = MediaConvertFormats.create(MediaConvertFormats.parseEncoders(encoders), MediaConvertAudioCodecs.parseEncoders(encoders)).filterNot { it.isAudioOnly }
            val input = File(root, "source.mkv")
            val generated = ffmpeg(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=160x128:rate=25", "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "1.2", "-c:v", "ffv1", "-c:a", "pcm_s16le", "-ac", "2", input.path))
            assertEquals(generated.second, 0, generated.first)
            val ffprobe = File(executable!!).resolveSibling(if (executable.endsWith(".exe")) "ffprobe.exe" else "ffprobe").path
            val failures = mutableListOf<String>()
            var count = 0
            fun checkPair(format: MediaConvertFormatInfo, codec: String, sourceFile: File, options: MediaConvertOptions, source: MediaConvertSource, suffix: String = "") {
                val label = "${format.extension}/$codec$suffix"
                val target = File(root, "output.${format.extension}")
                val encoded = ffmpeg(MediaConvertCommand.build(sourceFile.path, target.path, format, options, source))
                if (encoded.first != 0) { failures += "$label encode: ${encoded.second.takeLast(500)}"; return }
                val probed = run(listOf(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name", "-of", "csv=p=0", target.path), "probe")
                val found = probed.second.lineSequence().firstOrNull { it.isNotBlank() }?.substringBefore(',')?.trim()
                if (found != codecIds.getOrDefault(codec, codec)) { failures += "$label probe: ${probed.second}"; return }
                val pcm = File(root, "decoded.pcm")
                val decoded = ffmpeg(listOf("-y", "-i", target.path, "-map", "0:a:0", "-vn", "-ar", "16000", "-ac", "1", "-f", "s16le", pcm.path))
                if (decoded.first != 0 || pcm.length() < 1000) { failures += "$label decode: ${decoded.second.takeLast(500)}"; return }
                count++
            }
            for (format in formats) for (codec in format.audioCodecs.filter { it != "auto" && it != "copy" }) {
                checkPair(format, codec, input, MediaConvertOptions(selectedAudioCodec = codec),
                    MediaConvertSource(true, 1.2, 25.0, audioSampleRate = 48000, audioChannels = 2))
            }
            val surround = File(root, "surround.mkv")
            val surroundGenerated = ffmpeg(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=160x128:rate=25", "-f", "lavfi", "-i", "anullsrc=r=96000:cl=5.1", "-t", "1.2", "-c:v", "ffv1", "-c:a", "pcm_s24le", surround.path))
            assertEquals(surroundGenerated.second, 0, surroundGenerated.first)
            val mkv = formats.first { it.extension == "mkv" }
            for (codec in listOf("libopencore_amrnb", "libvo_amrwbenc", "libspeex", "adpcm_swf", "real_144", "wmav1", "dca", "tta", "wavpack", "alac", "pcm_s24le").filter { it in mkv.audioCodecs }) {
                checkPair(mkv, codec, surround, MediaConvertOptions(selectedAudioCodec = codec, audioBitrate = "192"),
                    MediaConvertSource(true, 1.2, 25.0, audioSampleRate = 96000, audioChannels = 6), " (96 kHz, 5.1)")
            }
            println("Encoded/probed/decoded $count audio/container pairs")
            assertTrue("Expected a broad audio/container matrix", count >= 100)
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
