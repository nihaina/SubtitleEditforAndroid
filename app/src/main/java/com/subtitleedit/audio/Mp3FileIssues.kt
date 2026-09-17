package com.subtitleedit.audio

internal data class Mp3FileIssues(
    val nonZeroStartTimeSeconds: Double? = null,
    val dataRateBelowNominalBitrate: Boolean = false
) {
    val hasIssues: Boolean
        get() = nonZeroStartTimeSeconds != null || dataRateBelowNominalBitrate

    companion object {
        fun from(
            startTimeSeconds: Double?,
            audioSizeBytes: Long?,
            durationSeconds: Double?,
            nominalBitrateBitsPerSecond: Double?
        ): Mp3FileIssues {
            val actualBitrate = if (audioSizeBytes != null && audioSizeBytes > 0L &&
                durationSeconds != null && durationSeconds.isFinite() && durationSeconds > 0.0
            ) {
                audioSizeBytes.toDouble() * 8.0 / durationSeconds
            } else null
            return Mp3FileIssues(
                nonZeroStartTimeSeconds = startTimeSeconds?.takeIf { it.isFinite() && it != 0.0 },
                dataRateBelowNominalBitrate = actualBitrate != null && actualBitrate.isFinite() &&
                    nominalBitrateBitsPerSecond != null && nominalBitrateBitsPerSecond.isFinite() &&
                    nominalBitrateBitsPerSecond > 0.0 &&
                    actualBitrate < nominalBitrateBitsPerSecond
            )
        }
    }
}
