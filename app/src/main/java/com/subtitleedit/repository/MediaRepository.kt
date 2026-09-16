package com.subtitleedit.repository

import com.subtitleedit.audio.Mp3FileIssues
import java.io.File

internal data class PreparedAudioFile(
    val playbackFile: File,
    val wasFixed: Boolean,
    val audioStreamIndex: Int? = null,
    val mp3Issues: Mp3FileIssues = Mp3FileIssues()
)

internal interface MediaRepository {
    suspend fun prepareAudio(
        audioFile: File,
        inspectVideoAudioTrack: Boolean = false
    ): PreparedAudioFile

    suspend fun getCacheKey(file: File): String

    fun release()
}
