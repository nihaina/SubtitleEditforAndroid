package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertAudioVolumeTest {
    private val formats = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders)
    private fun command(ext: String, percent: Int, codec: String = "auto", source: MediaConvertSource = MediaConvertSource(true, 1.0)) =
        MediaConvertCommand.build("in", "out.$ext", formats.first { it.extension == ext },
            MediaConvertOptions(selectedAudioCodec = codec, audioVolumePercent = percent), source)
    private fun List<String>.value(flag: String) = indexOf(flag).takeIf { it >= 0 }?.let { get(it + 1) }

    @Test
    fun defaultVolumeLeavesAudioProcessingUnchangedAndPercentagesAreExact() {
        for (ext in listOf("mp4", "mkv", "mp3", "wav", "flac")) {
            assertNull(command(ext, 100).value("-af"))
            assertEquals("volume=0", command(ext, 0).value("-af"))
            assertEquals("volume=0.5", command(ext, 50).value("-af"))
            assertEquals("volume=1.01", command(ext, 101).value("-af"))
            assertEquals("volume=10", command(ext, 1000).value("-af"))
        }
        assertThrows(IllegalArgumentException::class.java) { command("wav", -1) }
        assertThrows(IllegalArgumentException::class.java) { command("wav", 1001) }
    }

    @Test
    fun copyDoesNotApplyAudioFilterAndBlackVideoUsesTheMappedAudio() {
        assertNull(command("mkv", 200, "copy").value("-af"))
        assertNull(command("mp3", 200, "copy").value("-af"))
        val synthetic = command("mp4", 200, source = MediaConvertSource(false, 1.0))
        assertEquals("volume=2", synthetic.value("-af"))
        assertTrue(synthetic.windowed(2).any { it == listOf("-map", "1:a:0") })
    }

    @Test
    fun sliderPlacesTheOriginalVolumeInTheMiddleAndRoundTripsEachPercent() {
        assertEquals(0f, MediaConvertAudioVolume.sliderPosition(0))
        assertEquals(0.5f, MediaConvertAudioVolume.sliderPosition(100))
        assertEquals(1f, MediaConvertAudioVolume.sliderPosition(1000))
        for (percent in 0..1000) assertEquals(percent, MediaConvertAudioVolume.percentAt(MediaConvertAudioVolume.sliderPosition(percent)))
        assertEquals(550, MediaConvertAudioVolume.percentAt(0.75f))
    }
}
