package com.subtitleedit.feature.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryImageViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun translucentControlsCoverTheImageWithoutResizingItsViewport() {
        val file = File.createTempFile("overlay-preview", ".png", compose.activity.cacheDir)
        val image = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }
        val controlsVisible = mutableStateOf(true)
        val files = listOf(file)
        val noAction = {}
        try {
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            compose.setContent {
                MaterialTheme(colorScheme = lightColorScheme(surfaceContainer = Color.Blue)) {
                    Box(Modifier.size(200.dp, 300.dp).testTag("image-with-overlays")) {
                        DirectoryImagePreview(
                            file = file,
                            files = files,
                            isSelected = false,
                            selectionTitle = null,
                            controlsVisible = controlsVisible.value,
                            quarterTurns = 0,
                            onPageChanged = {},
                            onNavigationClick = noAction,
                            onToggleSelection = noAction,
                            onToggleControls = { controlsVisible.value = !controlsVisible.value },
                            bottomBar = {
                                MainBottomBar(
                                    state = MainBottomBarState(R.id.nav_directory, false, 0, null, false, false, false, false, false),
                                    onTopLevelPageSelected = {},
                                    onCopy = noAction, onMove = noAction, onRename = noAction, onDelete = noAction,
                                    onConvertSelection = noAction, onCompressSelection = noAction,
                                    onEditTagsSelection = noAction, onClearCacheSelection = noAction,
                                    onShowSelectionProperties = noAction, onConfirmDestination = noAction,
                                    onCancelDestination = noAction, onProcessMediaSelection = noAction,
                                    previewActions = { DirectoryImagePreviewActions(noAction, noAction, noAction, noAction) },
                                    previewControlsVisible = controlsVisible.value
                                )
                            }
                        )
                    }
                }
            }
            val label = compose.activity.getString(R.string.directory_image_preview)
            compose.waitUntil(timeoutMillis = 5000) {
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
            }
            val viewport = compose.onNodeWithContentDescription(label)
            val originalViewport = viewport.fetchSemanticsNode()
            val root = compose.onNodeWithTag("image-with-overlays")
            val masked = root.captureToImage().toPixelMap()
            val sampleRows = listOf(masked.height / 24, masked.height * 23 / 24)
            for (row in sampleRows) {
                assertEquals(0.2f, masked[2, row].red, 0.03f)
                assertEquals(0.8f, masked[2, row].blue, 0.03f)
            }

            root.performTouchInput { click(center) }
            compose.waitUntil(timeoutMillis = 5000) { !controlsVisible.value }
            val unmasked = root.captureToImage().toPixelMap()
            for (row in sampleRows) assertEquals(Color.Red, unmasked[2, row])
            val currentViewport = viewport.fetchSemanticsNode()
            assertEquals(originalViewport.id, currentViewport.id)
            assertEquals(originalViewport.boundsInRoot, currentViewport.boundsInRoot)

            root.performTouchInput { click(Offset(2f, sampleRows.last().toFloat())) }
            compose.waitUntil(timeoutMillis = 5000) { controlsVisible.value }
        } finally {
            image.recycle()
            file.delete()
        }
    }

    @Test fun horizontalDragCanReachTheZoomedImageEdgeAndPageWithoutLiftingFirst() {
        val first = File.createTempFile("first-preview", ".png", compose.activity.cacheDir)
        val second = File.createTempFile("second-preview", ".png", compose.activity.cacheDir)
        val files = listOf(first, second)
        val image = Bitmap.createBitmap(100, 64, Bitmap.Config.ARGB_8888)
        try {
            for (file in files) {
                file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            val current = mutableStateOf(first)
            compose.setContent {
                Box(Modifier.size(width = 200.dp, height = 300.dp)) {
                    DirectoryImagePreview(
                        file = current.value,
                        files = files,
                        isSelected = false,
                        selectionTitle = null,
                        controlsVisible = true,
                        quarterTurns = 0,
                        onPageChanged = { current.value = it },
                        onNavigationClick = {},
                        onToggleSelection = {},
                        onToggleControls = {}
                    )
                }
            }
            val label = compose.activity.getString(R.string.directory_image_preview)
            compose.waitUntil(timeoutMillis = 5000) {
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
            }
            val preview = compose.onNodeWithContentDescription(label)
            preview.performTouchInput { doubleClick(center + Offset(width * 0.27f, 0f)) }
            preview.performTouchInput {
                down(center)
                for (step in 1..5) moveTo(center - Offset(width * 0.02f * step, 0f))
                advanceEventTime(100)
                up()
            }
            compose.runOnIdle { assertEquals(first, current.value) }

            preview.performTouchInput {
                val start = center + Offset(width * 0.35f, 0f)
                down(start)
                for (step in 1..10) moveTo(start - Offset(width * 0.07f * step, 0f))
                up()
            }
            compose.runOnIdle { assertEquals(second, current.value) }
        } finally {
            image.recycle()
            first.delete()
            second.delete()
        }
    }

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
