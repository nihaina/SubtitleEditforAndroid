package com.subtitleedit.feature.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryImageRegionsTest {
    @Test fun largeImageUsesASampledOverviewAndAddsDetailOnlyWhereVisible() {
        val source = IntSize(20_000, 12_000)
        val viewport = IntSize(1080, 1920)
        val overview = directoryImageOverview(source, 2048)
        assertEquals(16, overview.sample)
        assertEquals(IntRect(0, 0, 20_000, 12_000), overview.bounds)
        assertNull(directoryImageDetail(source, viewport, 0.054f, Offset.Zero, 0, false, overview.sample))

        val partiallyZoomed = checkNotNull(directoryImageDetail(source, viewport, 0.2f, Offset.Zero, 0, false, overview.sample))
        assertEquals(4, partiallyZoomed.sample)
        val pixelForPixel = checkNotNull(directoryImageDetail(source, viewport, 1f, Offset.Zero, 0, false, overview.sample))
        assertEquals(1, pixelForPixel.sample)
        assertTrue(pixelForPixel.bounds.width in viewport.width..(viewport.width + 512))
        assertTrue(pixelForPixel.bounds.height in viewport.height..(viewport.height + 512))
    }

    @Test fun viewportPanIsMappedBackToTheCorrectSourceRegionAfterRotationAndMirroring() {
        val source = IntSize(8192, 4096)
        val viewport = IntSize(1024, 768)
        val translation = Offset(-1024f, -2048f)

        assertEquals(IntRect(5632, 512, 6656, 1536),
            directoryImageDetail(source, viewport, 1f, translation, 1, false, 4)?.bounds)
        assertEquals(IntRect(1536, 512, 2560, 1536),
            directoryImageDetail(source, viewport, 1f, translation, 1, true, 4)?.bounds)
    }

    @Test fun detailSamplingStaysWithinTheBitmapBudgetOnALargeViewport() {
        val source = IntSize(50_000, 30_000)
        val viewport = IntSize(4300, 3500)
        val tile = checkNotNull(directoryImageDetail(source, viewport, 0.6f, Offset.Zero, 0, false, 32))
        assertEquals(4, tile.sample)
    }
}
