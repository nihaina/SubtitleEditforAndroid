package com.subtitleedit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional host checks verify decoded frames, timing, pixel negotiation and real two-pass execution. */
class MediaConvertVideoSettingsFfmpegTest {
    @Test fun videoSettingsProduceDecodableOutputAndRealTwoPassFiles() {
        val executable = System.getenv("MEDIA_CONVERT_SMOKE_FFMPEG")
        assumeTrue("Set MEDIA_CONVERT_SMOKE_FFMPEG for video checks", !executable.isNullOrBlank())
        val ffmpeg = File(executable!!)
        val ffprobe = File(ffmpeg.parentFile, "ffprobe.exe")
        assumeTrue("ffprobe.exe must be next to ffmpeg.exe", ffprobe.isFile)
        val root = Files.createTempDirectory("video settings checks ").toFile()
        fun run(args: List<String>, binary: File = ffmpeg): String {
            val log = File(root, "process.log")
            val process = ProcessBuilder(listOf(binary.path, "-v", "error") + args).directory(root)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw AssertionError("Timeout: $args") }
            assertEquals("$args\n${log.readText()}", 0, process.exitValue())
            return log.readText().replace("\r\n", "\n")
        }
        fun videoInfo(file: File) = run(listOf("-select_streams", "v:0", "-show_entries", "stream=width,height,avg_frame_rate,pix_fmt", "-of", "default=nw=1", file.path), ffprobe)
        fun duration(file: File) = run(listOf("-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.path), ffprobe).trim().toDouble()
        fun decoded(file: File, target: String, filters: String? = null): ByteArray {
            val output = File(root, target)
            run(listOf("-y", "-i", file.path, "-map", "0:v:0") +
                (filters?.let { listOf("-vf", it) } ?: emptyList()) + listOf("-pix_fmt", "yuv420p", "-f", "rawvideo", output.path))
            return output.readBytes()
        }
        try {
            val input = File(root, "source.mkv")
            run(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=160x96:rate=25", "-f", "lavfi", "-i", "sine=sample_rate=48000",
                "-t", "2.4", "-c:v", "ffv1", "-c:a", "pcm_s16le", input.path))
            val detected = MediaConvertFormats.parseEncoders(run(listOf("-hide_banner", "-encoders")))
            val formats = MediaConvertFormats.create(detected)
            val mp4 = formats.first { it.extension == "mp4" }
            val source = MediaConvertSource(true, 2.4, 25.0, audioSampleRate = 48000, audioChannels = 1)
            val output = File(root, "output.mp4")
            var scenarios = 0
            val transforms = listOf(
                MediaConvertVideoSettings(rotation = MediaConvertRotation.CLOCKWISE, flipHorizontal = true, flipVertical = true) to "transpose=clock,hflip,vflip",
                MediaConvertVideoSettings(rotation = MediaConvertRotation.COUNTERCLOCKWISE) to "transpose=cclock",
                MediaConvertVideoSettings(rotation = MediaConvertRotation.HALF) to "hflip,vflip",
                MediaConvertVideoSettings(deinterlace = MediaConvertDeinterlace.ON) to "yadif=mode=send_frame:parity=auto:deint=all",
                MediaConvertVideoSettings(deinterlace = MediaConvertDeinterlace.AUTO) to "yadif=mode=send_frame:parity=auto:deint=interlaced",
                MediaConvertVideoSettings(filter = MediaConvertVideoFilter.GRAYSCALE) to "hue=s=0",
                MediaConvertVideoSettings(filter = MediaConvertVideoFilter.YELLOW) to "colorchannelmixer=rr=1:gg=1:bb=0.5",
                MediaConvertVideoSettings(filter = MediaConvertVideoFilter.RED) to "colorchannelmixer=rr=1:gg=0.65:bb=0.65",
                MediaConvertVideoSettings(filter = MediaConvertVideoFilter.GREEN) to "colorchannelmixer=rr=0.65:gg=1:bb=0.65",
                MediaConvertVideoSettings(filter = MediaConvertVideoFilter.BLUE) to "colorchannelmixer=rr=0.65:gg=0.65:bb=1",
                MediaConvertVideoSettings(filter = MediaConvertVideoFilter.GOLD) to "colorchannelmixer=rr=0.393:rg=0.769:rb=0.189:gr=0.349:gg=0.686:gb=0.168:br=0.272:bg=0.534:bb=0.131"
            )
            for ((settings, reference) in transforms) {
                run(MediaConvertCommand.build(input.path, output.path, mp4, MediaConvertOptions(videoSettings = settings.copy(crf = 0)), source))
                assertArrayEquals(settings.toString(), decoded(input, "reference.raw", reference), decoded(output, "actual.raw"))
                if (settings.rotation in listOf(MediaConvertRotation.CLOCKWISE, MediaConvertRotation.COUNTERCLOCKWISE)) {
                    val info = videoInfo(output)
                    assertTrue(info, info.contains("width=96\nheight=160"))
                }
                scenarios++
            }
            val timed = MediaConvertOptions(videoSettings = MediaConvertVideoSettings(frameRate = "12", keyframeSeconds = "1", crf = 20))
            run(MediaConvertCommand.build(input.path, output.path, mp4, timed, source))
            assertTrue(videoInfo(output).contains("avg_frame_rate=12/1"))
            val frameRows = run(listOf("-select_streams", "v:0", "-show_entries", "frame=key_frame,best_effort_timestamp_time", "-of", "csv=p=0", output.path), ffprobe)
            val keyTimes = frameRows.lineSequence().map { it.split(',') }.filter { it.firstOrNull() == "1" }.mapNotNull { it.getOrNull(1)?.toDoubleOrNull() }.toList()
            for (time in listOf(0.0, 1.0, 2.0)) assertTrue("Missing keyframe at $time: $frameRows", keyTimes.any { kotlin.math.abs(it - time) < 0.085 })
            assertTrue(duration(output) >= 2.3)
            scenarios++

            val rotated = File(root, "rotation-metadata.mp4")
            val base = File(root, "rotation-base.mp4")
            run(MediaConvertCommand.build(input.path, base.path, mp4, MediaConvertOptions(videoSettings = MediaConvertVideoSettings(crf = 0)), source))
            run(listOf("-y", "-i", base.path, "-map", "0", "-c", "copy", "-metadata:s:v:0", "rotate=90", rotated.path))
            run(MediaConvertCommand.build(rotated.path, output.path, mp4,
                MediaConvertOptions(videoSettings = MediaConvertVideoSettings(crf = 0, rotation = MediaConvertRotation.CLOCKWISE)), source))
            assertArrayEquals("Source orientation and user rotation", decoded(rotated, "rotation-reference.raw", "transpose=clock"), decoded(output, "rotation-actual.raw"))
            scenarios++

            for (codec in MediaConvertVideoSettingsSupport.crfCodecs.filter { it in detected }) {
                val format = formats.first { codec in it.videoCodecs }
                run(MediaConvertCommand.build(input.path, File(root, "crf.${format.extension}").path, format,
                    MediaConvertOptions(selectedVideoCodec = codec, videoSettings = MediaConvertVideoSettings(crf = 23)), source))
                scenarios++
            }

            for (codec in MediaConvertVideoSettingsSupport.twoPassCodecs.filter { it in detected }) {
                val format = formats.first { codec in it.videoCodecs && it.extension in listOf("mp4", "mkv", "webm") }
                val target = File(root, "two-pass-${codec}.${format.extension}")
                val options = MediaConvertOptions(selectedVideoCodec = codec,
                    videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "200"),
                    videoSettings = MediaConvertVideoSettings(twoPass = true, keyframeSeconds = "1"))
                val stats = File(root, "stats-$codec").path
                val plan = MediaConvertCommand.plan(input.path, target.path, format, options, source, stats)
                assertEquals(2, plan.size)
                for ((index, command) in plan.withIndex()) {
                    run(if (index == 0) command.dropLast(1) + File(root, "analysis.null").path else command)
                    if (index == 0) assertTrue("Missing pass stats for $codec", root.listFiles()!!.any { it.name.startsWith("stats-$codec") && it.length() > 0 })
                }
                assertTrue(duration(target) >= 2.3)
                assertTrue(run(listOf("-select_streams", "a:0", "-show_entries", "stream=codec_type", "-of", "csv=p=0", target.path), ffprobe).contains("audio"))
                run(listOf("-i", target.path, "-f", "null", "-"))
                scenarios++
            }
            for (codec in detected.filter { it in formats.flatMap { format -> format.videoCodecs } }) {
                val pixels = MediaConvertVideoSettingsSupport.parsePixelFormats(run(listOf("-h", "encoder=$codec")))
                val format = formats.first { codec in it.videoCodecs && !it.isAudioOnly }
                for (pixel in pixels) {
                    val target = File(root, "pixel.${format.extension}")
                    run(MediaConvertCommand.build(input.path, target.path, format,
                        MediaConvertOptions(selectedVideoCodec = codec, videoSettings = MediaConvertVideoSettings(pixelFormat = pixel, keyframeSeconds = "1", frameRate = "25")), source, pixels))
                    run(listOf("-i", target.path, "-f", "null", "-"))
                    scenarios++
                }
            }
            val large = File(root, "large.mkv")
            run(listOf("-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25", "-f", "lavfi", "-i", "sine=sample_rate=48000",
                "-t", "6", "-c:v", "ffv1", "-c:a", "pcm_s16le", large.path))
            for (percent in MediaConvertVideoSettingsSupport.sizePercents) {
                val budget = MediaConvertOptions(audioBitrate = "128", videoSettings = MediaConvertVideoSettings(sizeLimitPercent = percent, twoPass = true))
                val sizePlan = MediaConvertCommand.plan(large.path, output.path, mp4, budget, source.copy(durationSeconds = 6.0, fileSizeBytes = large.length()), File(root, "size-stats").path)
                for ((index, command) in sizePlan.withIndex()) run(if (index == 0) command.dropLast(1) + File(root, "size.null").path else command)
                assertTrue("Output was truncated", duration(output) >= 5.9)
                val targetSize = large.length() * percent / 100
                assertTrue("Size target missed for $percent%: ${output.length()} vs $targetSize", output.length() <= targetSize * 1.15)
                if (percent == 10) assertTrue("Size target was not approached", output.length() >= targetSize * 0.70)
                scenarios++
            }
            val audioOnly = File(root, "audio.wav")
            run(listOf("-y", "-i", input.path, "-map", "0:a:0", "-c:a", "pcm_s16le", audioOnly.path))
            val generated = MediaConvertOptions(resolution = MediaConvertResolutionSettings(MediaConvertResolution.CUSTOM, 160, 96),
                videoBitrate = MediaConvertVideoBitrateSettings(MediaConvertBitrateMode.CUSTOM, "200"),
                videoSettings = MediaConvertVideoSettings(twoPass = true, rotation = MediaConvertRotation.CLOCKWISE))
            val generatedPlan = MediaConvertCommand.plan(audioOnly.path, output.path, mp4, generated,
                source.copy(hasVideo = false), File(root, "generated-stats").path)
            for ((index, command) in generatedPlan.withIndex()) run(if (index == 0) command.dropLast(1) + File(root, "generated.null").path else command)
            assertTrue(duration(output) >= 2.3)
            assertTrue(videoInfo(output).contains("width=160\nheight=96"))
            scenarios++
            println("Verified $scenarios video settings scenarios, decoded transforms, timing, all supported pixel choices and two-pass codecs")
        } finally { root.deleteRecursively() }
    }
}
