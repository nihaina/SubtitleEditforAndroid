package com.subtitleedit.feature.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryImageViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pinchEnlargesTheImageAndQuarterTurnRefitsItWithoutReplacingTheViewport() {
        val quarterTurns = mutableIntStateOf(0)
        val bitmap = Bitmap.createBitmap(32, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        val image = bitmap.asImageBitmap()
        compose.setContent {
            Box(Modifier.size(200.dp).background(Color.Black)) {
                DirectoryImageViewport(image, quarterTurns.intValue)
            }
        }
        val preview = compose.onNodeWithContentDescription(compose.activity.getString(R.string.directory_image_preview))
        val previewId = preview.fetchSemanticsNode().id
        val initial = preview.captureToImage().toPixelMap()
        assertEquals(Color.Black, initial[initial.width / 2, initial.height / 4])
        assertEquals(Color.Red, initial[initial.width / 4, initial.height / 2])

        repeat(2) {
            preview.performTouchInput {
                pinch(
                    start0 = center - Offset(width * 0.07f, 0f),
                    end0 = center - Offset(width * 0.36f, 0f),
                    start1 = center + Offset(width * 0.07f, 0f),
                    end1 = center + Offset(width * 0.36f, 0f)
                )
            }
        }
        val zoomed = preview.captureToImage().toPixelMap()
        assertEquals(Color.Red, zoomed[zoomed.width / 2, zoomed.height / 10])

        compose.runOnIdle { quarterTurns.intValue = 1 }
        val rotated = preview.captureToImage().toPixelMap()
        assertEquals(Color.Red, rotated[rotated.width / 2, rotated.height / 4])
        assertEquals(Color.Black, rotated[rotated.width / 4, rotated.height / 2])
        assertEquals(previewId, preview.fetchSemanticsNode().id)
    }

    @Test fun draggingPastTheEdgeMovesTheImageWithoutStretchingAndSpringsBack() {
        val image = Bitmap.createBitmap(32, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }.asImageBitmap()
        compose.setContent {
            Box(Modifier.size(200.dp).background(Color.Black)) {
                DirectoryImageViewport(image, quarterTurns = 0, canPage = { true })
            }
        }
        val preview = compose.onNodeWithContentDescription(compose.activity.getString(R.string.directory_image_preview))
        fun redRows() = preview.captureToImage().toPixelMap().let { pixels ->
            (0 until pixels.height).filter { row -> pixels[pixels.width / 2, row].red > 0.5f }
        }

        val initial = redRows()
        preview.performTouchInput {
            down(center)
            moveTo(center + Offset(0f, height * 0.2f))
            moveTo(center + Offset(0f, height * 0.4f))
        }
        val dragged = redRows()
        assertTrue(dragged.first() > initial.first())
        assertEquals(initial.size.toFloat(), dragged.size.toFloat(), 1f)
        preview.performTouchInput { up() }
        assertEquals(initial, redRows())
    }

    @Test fun doubleTapAfterPartialPinchZoomCentersTheTappedAreaAndThenRestoresTheImage() {
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            for (row in 0 until height) for (column in 60..72) setPixel(column, row, android.graphics.Color.RED)
        }.asImageBitmap()
        var taps = 0
        compose.setContent {
            Box(Modifier.size(200.dp).background(Color.Black)) {
                DirectoryImageViewport(image, quarterTurns = 0, onTap = { taps++ })
            }
        }
        val preview = compose.onNodeWithContentDescription(compose.activity.getString(R.string.directory_image_preview))
        fun centerColor() = preview.captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }

        preview.performTouchInput {
            pinch(center - Offset(width * 0.16f, 0f), center - Offset(width * 0.26f, 0f),
                center + Offset(width * 0.16f, 0f), center + Offset(width * 0.26f, 0f))
        }
        assertEquals(Color.Black, centerColor())
        preview.performTouchInput { doubleClick(center + Offset(width * 0.25f, 0f)) }
        assertEquals(Color.Red, centerColor())
        preview.performTouchInput { doubleClick(center) }
        assertEquals(Color.Black, centerColor())
        compose.runOnIdle { assertEquals(0, taps) }
    }

    @Test fun fastPanContinuesToMoveAfterTheFingerLifts() {
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            for (row in 0 until height) for (column in 46..53) setPixel(column, row, android.graphics.Color.RED)
        }.asImageBitmap()
        compose.setContent {
            Box(Modifier.size(200.dp).background(Color.Black)) {
                DirectoryImageViewport(image, quarterTurns = 0)
            }
        }
        val preview = compose.onNodeWithContentDescription(compose.activity.getString(R.string.directory_image_preview))
        fun bandStart() = preview.captureToImage().toPixelMap().let { pixels ->
            (0 until pixels.width).first { column -> pixels[column, pixels.height / 2].red > 0.5f }
        }

        preview.performTouchInput { doubleClick(center) }
        bandStart()
        compose.mainClock.autoAdvance = false
        try {
            preview.performTouchInput {
                down(center)
                for (step in 1..5) moveTo(center + Offset(width * 0.026f * step, 0f), delayMillis = 16L)
            }
            val beforeRelease = bandStart()
            preview.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(96L)
            assertTrue(bandStart() > beforeRelease)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
