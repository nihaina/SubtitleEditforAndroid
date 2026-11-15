package com.subtitleedit.util

import com.subtitleedit.model.BrowserFileMetadata

/** Application-wide snapshots shared by browsing, recursive library scans and media decoding. */
internal object SharedFileCache {
    val attributes = FileMetadataCache(reader = { file, revision ->
        BrowserFileMetadata.read(file, revision)?.let {
            it.copy(restricted = AndroidDirectoryPolicy.isRestricted(file, it.isDirectory))
        }
    })
    val directories = BrowserDirectoryCache(attributes)
}
