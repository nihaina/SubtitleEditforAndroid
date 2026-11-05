package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MediaConvertAudioOutputFfmpegTest {
    @Test
    fun audioOutputsEncodeProbeAndDecodeWithDefaultAndAdaptedSettings() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG for audio output checks", !executable.isNullOrBlank())
        val root = Files.createTempDirectory("audio output matrix ").toFile()
        fun run(command: List<String>): Pair<Int, String> {
            val log = File(root, "process.log")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("Timeout: $command") }
            return process.exitValue() to log.readText()
        }
        fun ffmpeg(args: List<String>) = run(listOf(executable!!, "-loglevel", "error", "-filter_threads", "1", "-threads", "1") + args)
        try {
            val encoders = ffmpeg(listOf("-hide_banner", "-loglevel", "info", "-encoders")).second
            val formats = MediaConvertAudioFormats.create(MediaConvertAudioCodecs.parseEncoders(encoders))
            val input = File(root, "source.wav")
            val surround = File(root, "surround.wav")
            for ((file, rate, layout) in listOf(Triple(input, 48000, "stereo"), Triple(surround, 96000, "7.1"))) {
                val result = ffmpeg(listOf("-y", "-f", "lavfi", "-i", "anullsrc=r=$rate:cl=$layout", "-t", "1.2", "-c:a", "pcm_s24le", file.path))
                assertEquals(result.second, 0, result.first)
            }
            val ffprobe = File(executable!!).resolveSibling(if (executable.endsWith(".exe")) "ffprobe.exe" else "ffprobe").path
            val failures = mutableListOf<String>()
            var count = 0
            val codecIds = mapOf("libopencore_amrnb" to "amr_nb", "libvo_amrwbenc" to "amr_wb", "libgsm" to "gsm", "libgsm_ms" to "gsm_ms", "dca" to "dts", "libmp3lame" to "mp3", "libopus" to "opus", "libspeex" to "speex", "libvorbis" to "vorbis", "real_144" to "ra_144")
            for (format in formats) for (codec in format.audioCodecs.filter { it != "auto" && it != "copy" }) {
                for (adapt in listOf(false, true)) {
                    val label = "${format.extension}/$codec/$adapt"
                    val output = File(root, "output.${format.extension}")
                    val options = MediaConvertOptions(selectedAudioCodec = codec, audioBitrate = if (adapt) "192" else "",
                        sampleRateIndex = if (adapt) MediaConvertCommand.sampleRates.indexOf(96000) else 0, channelIndex = if (adapt) 8 else 0)
                    val source = MediaConvertSource(false, 1.2, audioSampleRate = if (adapt) 96000 else 48000, audioChannels = if (adapt) 8 else 2,
                        audioChannelLayout = if (adapt) "7.1" else "stereo")
                    val encoded = ffmpeg(MediaConvertCommand.build(if (adapt) surround.path else input.path, output.path, format, options, source))
                    if (encoded.first != 0) { failures += "$label encode: ${encoded.second.takeLast(500)}"; continue }
                    val probe = run(listOf(ffprobe, "-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "default=nw=1", output.path))
                    if (probe.first != 0 || "codec_name=${codecIds.getOrDefault(codec, codec)}" !in probe.second || "codec_type=video" in probe.second) {
                        failures += "$label probe: ${probe.second.takeLast(500)}"; continue
                    }
                    val decoded = ffmpeg(listOf("-i", output.path, "-map", "0:a:0", "-vn", "-f", "null", "-"))
                    if (decoded.first != 0) { failures += "$label decode: ${decoded.second.takeLast(500)}"; continue }
                    count++
                }
            }
            for (format in formats) {
                val codec = format.audioEncoder("auto")
                val indices = MediaConvertAudioCodecs.sampleRateIndices(codec, format.formatName).filter { it != 0 }
                for (index in listOf(indices.first(), indices.last()).distinct()) {
                    val label = "${format.extension}/$codec/${MediaConvertCommand.sampleRates[index]}Hz"
                    val output = File(root, "output.${format.extension}")
                    val encoded = ffmpeg(MediaConvertCommand.build(input.path, output.path, format,
                        MediaConvertOptions(sampleRateIndex = index), MediaConvertSource(false, 1.2, audioSampleRate = 48000, audioChannels = 2)))
                    if (encoded.first != 0) { failures += "$label encode: ${encoded.second.takeLast(500)}"; continue }
                    val probe = run(listOf(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate", "-of", "csv=p=0", output.path))
                    // Opus always decodes at 48 kHz; its selected input rate is not the decoded stream rate.
                    val expectedRate = if (codec == "libopus") 48000 else MediaConvertCommand.sampleRates[index]
                    if (probe.first != 0 || probe.second.lineSequence().firstOrNull()?.trim()?.toIntOrNull() != expectedRate) {
                        failures += "$label rate probe: ${probe.second}"; continue
                    }
                    val decoded = ffmpeg(listOf("-i", output.path, "-map", "0:a:0", "-vn", "-f", "null", "-"))
                    if (decoded.first != 0) { failures += "$label decode: ${decoded.second.takeLast(500)}"; continue }
                    count++
                }
            }
            println("Encoded/probed/decoded $count audio output/container cases across ${formats.size} formats")
            assertTrue("Expected broad audio output coverage", count >= 100)
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
