package com.subtitleedit.editor

import kotlin.math.abs

/** Fills the gaps between mpv audio position updates while playback is advancing. */
internal class AudioPlaybackClock {
    private var anchorPositionMs = 0.0
    private var anchorTimeNs = 0L
    private var lastSampleTimeNs = 0L
    private var lastSamplePositionMs = 0L
    private var speed = 1.0
    private var playing = false

    @Synchronized
    fun position(nowNs: Long): Long = estimate(nowNs).toLong().coerceAtLeast(0L)

    @Synchronized
    fun reset(positionMs: Long, nowNs: Long) {
        anchorPositionMs = positionMs.coerceAtLeast(0L).toDouble()
        anchorTimeNs = nowNs
        lastSampleTimeNs = nowNs
        lastSamplePositionMs = positionMs.coerceAtLeast(0L)
    }

    @Synchronized
    fun setPlaying(value: Boolean, nowNs: Long) {
        if (playing == value) return
        anchorPositionMs = estimate(nowNs)
        anchorTimeNs = nowNs
        lastSampleTimeNs = nowNs
        playing = value
    }

    @Synchronized
    fun setSpeed(value: Float, nowNs: Long) {
        anchorPositionMs = estimate(nowNs)
        anchorTimeNs = nowNs
        lastSampleTimeNs = nowNs
        speed = value.toDouble()
    }

    @Synchronized
    fun acceptSample(positionMs: Long, nowNs: Long) {
        val raw = positionMs.coerceAtLeast(0L)
        val predicted = estimate(nowNs)
        if (!playing || raw < lastSamplePositionMs - CLOCK_DISCONTINUITY_MS ||
            abs(raw - predicted) > CLOCK_DISCONTINUITY_MS
        ) {
            reset(raw, nowNs)
        } else if (raw > predicted) {
            anchorPositionMs = raw.toDouble()
            anchorTimeNs = nowNs
            lastSampleTimeNs = nowNs
        } else if (raw > lastSamplePositionMs) {
            lastSampleTimeNs = nowNs
        }
        lastSamplePositionMs = raw
    }

    private fun estimate(nowNs: Long): Double {
        if (!playing) return anchorPositionMs
        val cappedTimeNs = minOf(nowNs, lastSampleTimeNs + MAX_EXTRAPOLATION_NS)
        val elapsedNs = (cappedTimeNs - anchorTimeNs).coerceAtLeast(0L)
        return anchorPositionMs + elapsedNs / 1_000_000.0 * speed
    }

    private companion object {
        const val MAX_EXTRAPOLATION_NS = 300_000_000L
        const val CLOCK_DISCONTINUITY_MS = 350L
    }
}
