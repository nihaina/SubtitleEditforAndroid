package com.subtitleedit.player

import org.junit.Assert.*
import org.junit.Test

class PlayerVolumeGestureTest {
    private class Gesture(var actual: Int) {
        val policy = PlayerVolumeGesture()
        var acceptedMaximum = 200
        val writes = mutableListOf<Int>()
        fun move(delta: Float) = policy.adjust(delta, actual) { requested ->
            writes += requested
            minOf(requested, acceptedMaximum).also { actual = it }
        }
        fun release() = policy.end(completed = true)
    }

    @Test fun crossingUnityLandsOnceThenBlocksTheEntireHeldGestureWithoutFeedback() {
        val g = Gesture(90)
        assertEquals(PlayerVolumeFeedback.BOUNDARY, g.move(40f).feedback)
        assertEquals(100, g.actual)
        repeat(4) {
            assertEquals(PlayerVolumeGestureResult(blockedAtUnity = true), g.move(50f))
            assertEquals(100, g.actual)
        }
        assertEquals(listOf(100), g.writes)
        g.release()
        assertEquals(PlayerVolumeFeedback.LIGHT, g.move(1f).feedback)
        assertEquals(101, g.actual)
    }

    @Test fun startingAtUnityRequiresAWarningAndReleaseBeforeBoost() {
        val g = Gesture(100)
        assertTrue(g.move(1f).blockedAtUnity)
        assertTrue(g.writes.isEmpty())
        g.release()
        assertFalse(g.move(10f).blockedAtUnity)
        assertEquals(110, g.actual)
    }

    @Test fun cancellationCannotAcknowledgeTheWarning() {
        val g = Gesture(100)
        assertTrue(g.move(1f).blockedAtUnity)
        g.policy.end(completed = false)
        assertTrue(g.move(1f).blockedAtUnity)
        assertEquals(100, g.actual)
    }

    @Test fun simplyReachingUnityDoesNotReplaceTheWarning() {
        val g = Gesture(90)
        assertEquals(PlayerVolumeFeedback.BOUNDARY, g.move(10f).feedback)
        g.release()
        assertTrue(g.move(1f).blockedAtUnity)
        g.release()
        g.move(1f)
        assertEquals(101, g.actual)
    }

    @Test fun zeroAndMaximumReportOneBoundaryAndDoNotBuzzWhileHeldAtTheLimit() {
        val g = Gesture(5)
        assertEquals(PlayerVolumeFeedback.BOUNDARY, g.move(-20f).feedback)
        assertEquals(0, g.actual)
        assertEquals(PlayerVolumeFeedback.NONE, g.move(-20f).feedback)
        g.release()
        g.actual = 195
        assertEquals(PlayerVolumeFeedback.BOUNDARY, g.move(20f).feedback)
        assertEquals(200, g.actual)
        repeat(3) { assertEquals(PlayerVolumeFeedback.NONE, g.move(20f).feedback) }
    }

    @Test fun aBoundaryIsReportedOnlyOncePerGestureEvenAfterReversing() {
        val g = Gesture(90)
        assertEquals(PlayerVolumeFeedback.BOUNDARY, g.move(10f).feedback)
        assertEquals(PlayerVolumeFeedback.LIGHT, g.move(-10f).feedback)
        assertEquals(PlayerVolumeFeedback.NONE, g.move(10f).feedback)
        assertTrue(g.move(1f).blockedAtUnity)
    }

    @Test fun loweringBoostLandsAtUnityAndRequiresANewWarningToRaiseItAgain() {
        val g = Gesture(150)
        assertEquals(PlayerVolumeFeedback.BOUNDARY, g.move(-100f).feedback)
        assertEquals(100, g.actual)
        assertTrue(g.move(1f).blockedAtUnity)
        g.move(-20f)
        assertEquals(80, g.actual)
        g.release()
        g.move(30f)
        assertEquals(100, g.actual)
        assertTrue(g.move(1f).blockedAtUnity)
    }

    @Test fun actualSystemQuantizationDeterminesTheBoundaryFeedback() {
        val policy = PlayerVolumeGesture()
        val atUnity = policy.adjust(4f, 93) { 100 }
        assertEquals(PlayerVolumeFeedback.BOUNDARY, atUnity.feedback)
        val blocked = policy.adjust(4f, 100) { error("A blocked move must not write volume") }
        assertEquals(PlayerVolumeGestureResult(blockedAtUnity = true), blocked)
    }

    @Test fun aRejectedSystemMaximumAndExternalChangesCannotUnlockSoftwareGain() {
        val g = Gesture(50).apply { acceptedMaximum = 60 }
        assertFalse(g.move(100f).blockedAtUnity)
        assertEquals(60, g.actual)
        g.release()
        g.move(200f)
        assertEquals(60, g.actual)
        assertTrue(g.writes.all { it <= 100 })

        val second = Gesture(100)
        second.move(1f)
        second.release()
        second.policy.begin(100)
        second.actual = 80
        second.move(50f)
        assertEquals(100, second.actual)
        assertTrue(second.move(1f).blockedAtUnity)
    }

    @Test fun resetDropsAReleaseAcknowledgementAndFractionalMovesAccumulate() {
        val g = Gesture(100)
        g.move(1f)
        g.release()
        g.policy.reset()
        assertTrue(g.move(1f).blockedAtUnity)
        val small = Gesture(10)
        repeat(4) { small.move(0.2f) }
        assertEquals(11, small.actual)
    }
}
