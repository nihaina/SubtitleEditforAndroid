package com.subtitleedit.repository

import com.subtitleedit.audio.Mp3FileIssues
import java.io.File

internal data class PreparedAudioFile(
    val playbackFile: File,
    val wasFixed: Boolean,
    val audioStreamIndex: Int? = null
)

internal interface MediaRepository {
    /** Runs a fresh MP3 standards inspection for this open request. */
    suspend fun inspectMp3(audioFile: File): Mp3FileIssues

    suspend fun prepareAudio(
        audioFile: File,
        inspectVideoAudioTrack: Boolean = false,
        forceMp3Preparation: Boolean = false
    ): PreparedAudioFile

    suspend fun getCacheKey(file: File): String

    fun release()
}
