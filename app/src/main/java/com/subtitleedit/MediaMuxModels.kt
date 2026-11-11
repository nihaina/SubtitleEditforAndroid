package com.subtitleedit

internal enum class MediaMuxMode { MERGE, SPLIT }
internal enum class MediaMuxColumn { VIDEO, AUDIO }
internal enum class MediaMuxAudioAction { REPLACE, ADD }

/** Identity belongs to a queue entry, not its path: reimporting creates a new output owner. */
internal data class MediaMuxFile(val id: String, val uri: String, val fileName: String, val kind: MediaMuxColumn)
internal data class MediaMuxResult(val uri: String, val fileName: String)
internal data class MediaMuxRow(
    val id: String,
    val video: MediaMuxFile? = null,
    val audio: MediaMuxFile? = null,
    val output: MediaMuxResult? = null
) {
    fun eligible(mode: MediaMuxMode): Boolean = video != null && when (mode) {
        MediaMuxMode.MERGE -> audio != null
        MediaMuxMode.SPLIT -> audio == null
    }
}

internal data class MediaMuxConflict(val videoName: String)
internal data class MediaMuxUiState(
    val rows: List<MediaMuxRow> = emptyList(),
    val mode: MediaMuxMode = MediaMuxMode.MERGE,
    val outputDirectory: String = "",
    val isRunning: Boolean = false,
    val isImporting: Boolean = false,
    val progress: Int = 0,
    val log: String = "",
    val conflict: MediaMuxConflict? = null
) {
    val canProcess: Boolean get() = !isRunning && !isImporting && rows.any { it.eligible(mode) }
}
