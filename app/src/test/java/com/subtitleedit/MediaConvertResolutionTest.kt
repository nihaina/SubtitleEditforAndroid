package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaConvertResolutionTest {
    @Test
    fun customDimensionsMustBeEvenAndWithinTheInputRange() {
        for (invalid in listOf(null, -2, 0, 1, 3, 4095, 4098)) {
            assertFalse(MediaConvertResolutionSettings(-1, invalid, 720).isValid)
            assertFalse(MediaConvertResolutionSettings(-1, 1280, invalid).isValid)
        }
        assertTrue(MediaConvertResolutionSettings(-1, 2, 4096).isValid)
        assertTrue(MediaConvertResolutionSettings().isValid)
        assertFalse(MediaConvertResolutionSettings(99).isValid)
    }

    @Test
    fun presetsKeepExistingSizesAndOfferBothOrientationsWithoutDuplicates() {
        val sizes = MediaConvertResolution.presets.filterNotNull()
        assertEquals(MediaConvertVideoSize(1920, 1080), MediaConvertResolution.presets[3])
        assertTrue(sizes.containsAll(listOf(MediaConvertVideoSize(1080, 1920), MediaConvertVideoSize(720, 1280), MediaConvertVideoSize(720, 480))))
        assertEquals(sizes.size, sizes.distinct().size)
        assertTrue(sizes.all { MediaConvertResolution.validDimension(it.width) && MediaConvertResolution.validDimension(it.height) })
    }

    @Test
    fun lockedRatioLinksEitherSideAndRoundsToEvenPixels() {
        assertEquals("1280", MediaConvertResolution.linkedDimension("720", 9.0 / 16, true))
        assertEquals("1280", MediaConvertResolution.linkedDimension("720", 16.0 / 9, false))
        assertEquals("480", MediaConvertResolution.linkedDimension("854", 16.0 / 9, true))
        assertEquals("", MediaConvertResolution.linkedDimension("4096", 9.0 / 16, true))
        assertEquals("", MediaConvertResolution.linkedDimension("", 1.0, true))
        assertEquals("", MediaConvertResolution.linkedDimension("100", 0.0, true))
    }

    @Test
    fun displayedSourceSizeUsesRotationAndItsAspectIncludesNonSquarePixels() {
        assertEquals(MediaConvertVideoSize(1080, 1920), MediaConvertVideoSize.fromStream(1920, 1080, "1:1", -90.0))
        assertEquals(MediaConvertVideoSize(1920, 1080), MediaConvertVideoSize.fromStream(1920, 1080, "1:1", 180.0))
        val rotated = MediaConvertVideoSize.fromStream(720, 576, "16:15", 90.0)!!
        assertEquals(576, rotated.width)
        assertEquals(720, rotated.height)
        assertEquals(0.75, rotated.aspectRatio, 0.0001)
        assertEquals(1.0, MediaConvertVideoSize.fromStream(100, 100, "0:0", Double.NaN)!!.aspectRatio, 0.0)
        assertNull(MediaConvertVideoSize.fromStream(0, 1080, null, 0.0))
    }

    @Test
    fun customResolutionFlowsIntoTheCommandAndBlackVideoCanvas() {
        val format = MediaConvertFormats.create(MediaConvertFormats.baselineEncoders).first { it.extension == "mp4" }
        val settings = MediaConvertResolutionSettings(-1, 720, 1280, true, MediaConvertFitMode.FILL)
        val options = MediaConvertOptions(resolution = settings)
        val real = MediaConvertCommand.build("input", "output", format, options, MediaConvertSource(true, 1.0))
        assertEquals(MediaConvertResolution.filter(settings), real[real.indexOf("-vf") + 1])
        val synthetic = MediaConvertCommand.build("input", "output", format, options, MediaConvertSource(false, 1.0))
        assertTrue("color=c=black:s=720x1280:r=30" in synthetic)
        assertFalse("-vf" in synthetic)
    }
}
