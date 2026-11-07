package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertAudioVbrTest {
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
    private fun format(extension: String) = formats.first { it.extension == extension }
    private fun command(extension: String, options: MediaConvertOptions, source: MediaConvertSource? = null) =
        MediaConvertCommand.build("input", "output.$extension", format(extension), options, source)
    private fun List<String>.value(flag: String) = indexOf(flag).takeIf { it >= 0 }?.let { get(it + 1) }

    @Test
    fun everyReferenceBandMapsToItsLameQualityAndReplacesBitrate() {
        val expectedBands = listOf("220…260", "190…250", "170…210", "150…195", "140…185", "120…150", "100…130", "80…120", "70…105", "45…85")
        for (quality in MediaConvertAudioVbr.qualities) {
            assertEquals(expectedBands[quality], MediaConvertAudioVbr.bitrateBand(quality))
            val args = command("mp3", MediaConvertOptions(audioVbrQuality = quality, audioBitrate = "192", audioVolumePercent = 150))
            assertEquals(quality.toString(), args.value("-q:a"))
            assertNull(args.value("-b:a"))
            assertEquals("volume=1.5", args.value("-af"))
        }
    }

    @Test
    fun vbrDoesNotNeedOriginalBitrateAndOffRestoresTheSavedBitrate() {
        val options = MediaConvertOptions(audioVbrQuality = 2, audioBitrate = MediaConvertAudioBitrate.ORIGINAL)
        assertEquals("2", command("mp3", options).value("-q:a"))
        val disabled = options.copy(audioVbrQuality = MediaConvertAudioVbr.OFF)
        val args = command("mp3", disabled, MediaConvertSource(false, 1.0, audioBitrateBps = 128000))
        assertEquals("128k", args.value("-b:a"))
        assertNull(args.value("-q:a"))
        assertThrows(IllegalArgumentException::class.java) { command("mp3", disabled) }
        assertNull(command("mp3", MediaConvertOptions()).value("-q:a"))
    }

    @Test
    fun qualityIsAppliedByEncoderOnlyForAudioOutput() {
        val options = MediaConvertOptions(audioVbrQuality = 3)
        assertEquals("3", command("wav", options.copy(selectedAudioCodec = "libmp3lame")).value("-q:a"))
        for (extension in listOf("aac", "m4a", "wav", "flac", "ogg", "opus", "mkv")) {
            assertNull(extension, command(extension, options).value("-q:a"))
        }
        assertNull(command("mp3", options.copy(selectedAudioCodec = "copy")).value("-q:a"))
        assertNull(command("mkv", options.copy(selectedAudioCodec = "libmp3lame")).value("-q:a"))
    }

    @Test
    fun invalidLameQualityIsRejectedInsteadOfBeingPassedToFfmpeg() {
        for (quality in listOf(-2, 10, Int.MAX_VALUE)) {
            assertFalse(MediaConvertAudioVbr.isValid(quality))
            assertThrows(IllegalArgumentException::class.java) { command("mp3", MediaConvertOptions(audioVbrQuality = quality)) }
        }
    }
}
