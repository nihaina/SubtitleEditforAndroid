package com.subtitleedit.audio

internal data class Mp3FileIssues(
    val nonZeroStartTimeSeconds: Double? = null,
    val missingSeekIndex: Boolean = false
) {
    val hasIssues: Boolean
        get() = nonZeroStartTimeSeconds != null || missingSeekIndex

    companion object {
        fun from(startTimeSeconds: Double?, hasSeekIndex: Boolean?): Mp3FileIssues =
            Mp3FileIssues(
                nonZeroStartTimeSeconds = startTimeSeconds?.takeIf { it.isFinite() && it != 0.0 },
                missingSeekIndex = hasSeekIndex == false
            )
    }
}
