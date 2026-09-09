package com.subtitleedit.repository

import java.io.File

internal data class PreparedAudioFile(
    val playbackFile: File,
    val wasFixed: Boolean,
    val audioStreamIndex: Int? = null
)

internal interface MediaRepository {
    suspend fun prepareAudio(
        audioFile: File,
        inspectVideoAudioTrack: Boolean = false
    ): PreparedAudioFile

    suspend fun getCacheKey(file: File): String

    fun release()
}
