package com.subtitleedit.player

import kotlin.math.roundToInt

internal enum class PlayerVolumeFeedback { NONE, LIGHT, BOUNDARY }
internal data class PlayerVolumeGestureResult(
    val feedback: PlayerVolumeFeedback = PlayerVolumeFeedback.NONE,
    val blockedAtUnity: Boolean = false
)

/** A warning can authorize boost only after a completed pointer gesture at 100%. */
internal class PlayerVolumeGesture {
    private var active = false
    private var value = 0f
    private var lastApplied = 0
    private var allowBoost = false
    private var warned = false
    private var releasedAfterWarning = false

    fun begin(actualPercent: Int) {
        active = true
        value = actualPercent.toFloat()
        lastApplied = actualPercent
        allowBoost = actualPercent > 100 || actualPercent == 100 && releasedAfterWarning
        releasedAfterWarning = false
        warned = false
    }

    fun adjust(delta: Float, actualPercent: Int, apply: (Int) -> Int): PlayerVolumeGestureResult {
        if (!active) begin(actualPercent)
        if (!delta.isFinite() || delta == 0f) return PlayerVolumeGestureResult()
        if (actualPercent != lastApplied) {
            // A system key or a route change invalidates an earlier warning acknowledgement.
            reset()
            begin(actualPercent)
        }
        var target = (value + delta).coerceIn(0f, 200f)
        if (!allowBoost && target > 100f) {
            target = 100f
            if (actualPercent == 100) {
                value = 100f
                warned = true
                return PlayerVolumeGestureResult(blockedAtUnity = true)
            }
        }
        // A fast movement must still land on the unity boundary before going below it.
        if (actualPercent > 100 && target < 100f) target = 100f
        val accepted = apply(target.roundToInt()).coerceIn(0, 200)
        value = target
        lastApplied = accepted
        if (accepted < 100) {
            allowBoost = false
            warned = false
            if (target >= 100f) value = accepted.toFloat() // Android rejected maximum volume.
        } else if (accepted == 100 && actualPercent > 100) {
            allowBoost = false
            warned = false
        }
        val feedback = when {
            accepted == actualPercent -> PlayerVolumeFeedback.NONE
            accepted in BOUNDARIES -> PlayerVolumeFeedback.BOUNDARY
            else -> PlayerVolumeFeedback.LIGHT
        }
        return PlayerVolumeGestureResult(feedback)
    }

    fun end(completed: Boolean) {
        if (!active) {
            if (!completed) releasedAfterWarning = false
            return
        }
        releasedAfterWarning = completed && warned && lastApplied == 100
        active = false
    }

    fun reset() {
        active = false
        releasedAfterWarning = false
        warned = false
    }

    private companion object { val BOUNDARIES = setOf(0, 100, 200) }
}
