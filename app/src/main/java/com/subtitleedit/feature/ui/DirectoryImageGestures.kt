package com.subtitleedit.feature.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

internal suspend fun PointerInputScope.detectImageTransforms(
    canPan: (Offset) -> Boolean,
    onStart: () -> Unit,
    onTransform: (centroid: Offset, zoom: Float, pan: Offset) -> Unit,
    onEnd: (Velocity) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onStart()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var lastMoveAt = down.uptimeMillis
        var multiTouch = false
        var accepted = false
        var totalPan = Offset.Zero
        var totalZoom = 1f
        var velocity = Velocity.Zero

        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed && it.position != it.previousPosition }) break
            multiTouch = multiTouch || event.changes.count { it.pressed } > 1
            if (!multiTouch) {
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed && accepted && change.uptimeMillis - lastMoveAt <= 80L) {
                    val maxVelocity = viewConfiguration.maximumFlingVelocity
                    velocity = tracker.calculateVelocity(Velocity(maxVelocity, maxVelocity))
                } else if (change.position != change.previousPosition) {
                    change.historical.forEach { tracker.addPosition(it.uptimeMillis, it.position) }
                    tracker.addPosition(change.uptimeMillis, change.position)
                    lastMoveAt = change.uptimeMillis
                }
            }
            if (event.changes.none { it.pressed }) break

            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            if (!accepted) {
                totalPan += pan
                totalZoom *= zoom
                val zoomMotion = abs(1f - totalZoom) * event.calculateCentroidSize(useCurrent = false)
                val slop = viewConfiguration.touchSlop
                accepted = zoomMotion > slop || totalPan.getDistance() > slop && canPan(totalPan)
            }
            if (accepted && (pan != Offset.Zero || zoom != 1f)) {
                onTransform(event.calculateCentroid(useCurrent = false), zoom, pan)
                event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
            }
        }
        onEnd(velocity)
    }
}
