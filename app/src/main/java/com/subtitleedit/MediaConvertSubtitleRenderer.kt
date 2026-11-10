package com.subtitleedit

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/** Probe the renderer itself rather than treating missing filter-list logs as an unavailable filter. */
internal object MediaConvertSubtitleRenderer {
    fun isAvailable(cacheDirectory: File, execute: (Array<String>) -> Boolean = { arguments ->
        ReturnCode.isSuccess(FFmpegKit.executeWithArguments(arguments).getReturnCode())
    }): Boolean {
        val subtitle = File.createTempFile("subtitle_filter_", ".srt", cacheDirectory)
        try {
            subtitle.writeText("1\n00:00:00,000 --> 00:00:01,000\nTest\n", Charsets.UTF_8)
            // Initializing the actual renderer distinguishes a missing filter from an empty listing.
            val filter = MediaConvertSubtitles.burnFilter(MediaConvertSubtitleInput(
                subtitle.absolutePath, subtitle.name, MediaConvertSubtitleType.BURN_IN))
            return execute(arrayOf("-hide_banner", "-loglevel", "error", "-nostats",
                "-f", "lavfi", "-i", "color=c=black:s=16x16:r=1",
                "-vf", filter, "-frames:v", "1", "-f", "null", "-"))
        } finally {
            subtitle.delete()
        }
    }
}
