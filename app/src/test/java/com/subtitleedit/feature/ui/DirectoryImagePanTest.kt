package com.subtitleedit.feature.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryImagePanTest {
    @Test fun maximumZoomKeepsTheBaselineAndExtendsToOriginalPixelSizeForLargeImages() {
        assertEquals(10f, directoryImageMaxZoom(0.5f), 0.01f)
        assertEquals(10f, directoryImageMaxZoom(0.1f), 0.01f)
        assertEquals(25f, directoryImageMaxZoom(0.04f), 0.01f)
        assertEquals(50f, directoryImageMaxZoom(0.02f), 0.01f)
    }

    @Test fun resistsOnlyThePartOfTheDragPastTheEdge() {
        assertEquals(70f, elasticImagePan(60f, 10f, 80f, 24f), 0.01f)
        assertEquals(86f, elasticImagePan(70f, 30f, 80f, 24f), 0.01f)
        assertEquals(-86f, elasticImagePan(-70f, -30f, 80f, 24f), 0.01f)
    }

    @Test fun capsTheOverdragAndDoesNotResistDraggingBackIn() {
        assertEquals(24f, elasticImagePan(0f, 200f, 0f, 24f), 0.01f)
        assertEquals(98f, elasticImagePan(104f, -6f, 80f, 24f), 0.01f)
        assertEquals(74f, elasticImagePan(98f, -24f, 80f, 24f), 0.01f)
    }

    @Test fun edgeSwipeUsesOnlyTheTravelBeyondThePanBoundary() {
        assertEquals(0f, previewEdgeDrag(0f, -40f, -20f, 80f), 0.01f)
        assertEquals(-10f, previewEdgeDrag(0f, -70f, -20f, 80f), 0.01f)
        assertEquals(-40f, previewEdgeDrag(-10f, -83f, -30f, 80f), 0.01f)
        assertEquals(0f, previewEdgeDrag(-40f, -92f, 5f, 80f), 0.01f)
        assertEquals(10f, previewEdgeDrag(0f, 70f, 20f, 80f), 0.01f)
    }

    @Test fun doubleTapCentersTheTappedRegionEvenAfterPartialPinchZoom() {
        val center = Offset(100f, 100f)
        assertEquals(
            ImagePreviewTransform(3f, Offset(-60f, 30f)),
            doubleTapImageTransform(ImagePreviewTransform(2f), Offset(40f, -20f), center, center)
        )
        assertEquals(
            ImagePreviewTransform(3f, Offset(200f, 0f)),
            doubleTapImageTransform(ImagePreviewTransform(), Offset(-95f, 12f), Offset(100f, 30f), center)
        )
    }

    @Test fun doubleTapAtOrAboveTheTargetZoomRestoresTheFittedImage() {
        val center = Offset(100f, 100f)
        for (zoom in listOf(3f, 5f)) {
            assertEquals(
                ImagePreviewTransform(),
                doubleTapImageTransform(ImagePreviewTransform(zoom, Offset(30f, -50f)), Offset(15f, 5f), center, center)
            )
        }
    }
}
