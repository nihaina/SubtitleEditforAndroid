package com.subtitleedit.player

import kotlin.math.pow
import kotlin.math.roundToInt

internal data class SystemMediaVolume(
    val index: Int,
    val minIndex: Int,
    val maxIndex: Int,
    val muted: Boolean = false,
)

internal interface PlayerSystemVolume {
    fun read(): SystemMediaVolume
    fun setIndex(index: Int)
}

internal data class PlayerVolumeSnapshot(
    val percent: Int,
    val softwarePercent: Int,
    val muted: Boolean,
    val system: SystemMediaVolume,
)

/** System volume owns 0..100%; software gain is available only at the actual system maximum. */
internal class PlayerVolumeController(private val system: PlayerSystemVolume) {
    var snapshot: PlayerVolumeSnapshot = snapshotOf(readSystem(), 100)
        private set

    fun refresh(): PlayerVolumeSnapshot {
        val actual = readSystem()
        // Hardware keys, system sliders and route/range changes must not leave stale amplification.
        val software = if (actual == snapshot.system) snapshot.softwarePercent else 100
        return snapshotOf(actual, software).also { snapshot = it }
    }

    fun setVolume(percent: Int): PlayerVolumeSnapshot {
        val requested = percent.coerceIn(0, 200)
        val before = readSystem()
        val target = if (requested > 100) before.maxIndex else {
            (before.minIndex + (before.maxIndex.toLong() - before.minIndex) * requested / 100.0)
                .roundToInt().coerceIn(before.minIndex, before.maxIndex)
        }
        if (target != before.index || before.muted && requested > 0) system.setIndex(target)
        // Android may reject or clamp a request. Never bypass its actual limit with software gain.
        val actual = readSystem()
        val software = if (requested > 100 && actual.index == actual.maxIndex && !isMuted(actual)) requested else 100
        return snapshotOf(actual, software).also { snapshot = it }
    }

    fun resetBoost(): PlayerVolumeSnapshot = snapshotOf(readSystem(), 100).also { snapshot = it }

    private fun readSystem(): SystemMediaVolume {
        val actual = system.read()
        val minimum = actual.minIndex.coerceAtLeast(0)
        val maximum = actual.maxIndex.coerceAtLeast(minimum)
        return actual.copy(index = actual.index.coerceIn(minimum, maximum), minIndex = minimum, maxIndex = maximum)
    }

    private fun snapshotOf(actual: SystemMediaVolume, softwarePercent: Int): PlayerVolumeSnapshot {
        val muted = isMuted(actual)
        val software = softwarePercent.coerceIn(100, 200)
            .takeIf { actual.index == actual.maxIndex && !muted } ?: 100
        val percent = when {
            muted -> 0
            software > 100 -> software
            actual.maxIndex == actual.minIndex -> 100
            else -> ((actual.index.toLong() - actual.minIndex) * 100.0 /
                (actual.maxIndex.toLong() - actual.minIndex)).roundToInt().coerceIn(0, 100)
        }
        return PlayerVolumeSnapshot(percent, software, muted, actual)
    }

    private fun isMuted(actual: SystemMediaVolume): Boolean = actual.muted || actual.index <= 0
}

internal object PlayerVolumePolicy {
    /** mpv cubes its volume property; invert that curve to cap 200% at a linear 2x gain. */
    fun mpvVolume(linearPercent: Int): Double =
        100.0 * (linearPercent.coerceIn(0, 200) / 100.0).pow(1.0 / 3.0)
}

/** The DAC readback drives playback and UI. Pending writes only accumulate consecutive key presses. */
internal class PlayerUsbHardwareVolume {
    var confirmedPercent: Int? = null
        private set
    private var requestedPercent: Int? = null
    private var requestDeadlineMs = 0L

    fun request(nowMs: Long, adjust: (Int) -> Int): Int? {
        val confirmed = confirmedPercent ?: return null
        val baseline = requestedPercent?.takeIf { nowMs < requestDeadlineMs } ?: confirmed
        val target = adjust(baseline).coerceIn(0, 100)
        if (target == baseline) return null
        requestedPercent = target
        requestDeadlineMs = nowMs + WRITE_TIMEOUT_MS
        return target
    }

    fun readBack(percent: Int, nowMs: Long): Int? {
        val previous = confirmedPercent
        confirmedPercent = percent
        val request = requestedPercent ?: return null
        // The cached value can match a reversal request before the DAC applies it.
        val changedToRequest = percent != previous && percent == request
        if (!changedToRequest && nowMs < requestDeadlineMs) return null
        clearRequest()
        return request
    }

    fun clearRequest() {
        requestedPercent = null
        requestDeadlineMs = 0L
    }

    fun reset() {
        confirmedPercent = null
        clearRequest()
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 1_500L
    }
}
