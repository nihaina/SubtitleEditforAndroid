package com.subtitleedit.player

import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.util.SharedFileCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

internal data class PlayerStoredMedia(
    val file: BrowserFileMetadata?,
    val track: MediaLibraryTrack?
)

/** Membership changes affect tags and covers even when the source file has not changed. */
internal fun observePlayerStoredMedia(path: String, repository: MediaLibraryRepository): Flow<PlayerStoredMedia> =
    combine(SharedFileCache.attributes.invalidationRevision, repository.revision) { _, _ ->
        PlayerStoredMedia(
            file = runCatching { SharedFileCache.attributes.get(File(path)) }.getOrNull(),
            track = runCatching { repository.getStoredTrack(path) }.getOrNull()
        )
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
