package com.subtitleedit.library

import com.subtitleedit.model.BrowserFileMetadata
import java.util.concurrent.Semaphore

internal data class MediaArtworkRequest(val size: Int, val allowVideoFrame: Boolean)
internal data class MissingMediaFields(
    val duration: Boolean,
    val tags: Boolean,
    val cover: Boolean,
    val artwork: MediaArtworkRequest?
)
internal data class LoadedMediaFields<T, A>(
    val durationMs: Long = 0L,
    val tags: T? = null,
    val cover: ByteArray? = null,
    val artwork: A? = null
)
internal data class CachedMediaFields<T, A>(val durationMs: Long, val tags: T, val artwork: A?)

/** Each path/version is enriched only for fields requested by its consumers. */
internal class IncrementalMediaCache<T : Any, A : Any>(
    private val emptyTags: T,
    private val load: (BrowserFileMetadata, MissingMediaFields, ByteArray?) -> LoadedMediaFields<T, A>,
    private val artworkWeight: (A) -> Int,
    private val maximumEntries: Int = 8192,
    private val maximumArtworkBytes: Long = 16L * 1024 * 1024,
    private val maximumCoverBytes: Long = 16L * 1024 * 1024,
    concurrency: Int = 2
) {
    private data class Key(val path: String, val version: String)
    private data class ArtworkKey(val file: Key, val request: MediaArtworkRequest)
    private data class NullableValue<V>(val value: V?)
    private class Entry<T>(val emptyTags: T) {
        var durationLoaded = false
        var durationMs = 0L
        var tagsLoaded = false
        var tags = emptyTags
        var detailsLoaded = false
        var details: T? = null
        var users = 0
    }

    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry<T>>(16, 0.75f, true)
    private val covers = LinkedHashMap<Key, NullableValue<ByteArray>>(16, 0.75f, true)
    private val artworks = LinkedHashMap<ArtworkKey, NullableValue<A>>(16, 0.75f, true)
    private val permits = Semaphore(concurrency, true)
    private var coverBytes = 0L
    private var artworkBytes = 0L

    init {
        require(maximumEntries > 0 && maximumArtworkBytes > 0 && maximumCoverBytes > 0 && concurrency > 0)
    }

    fun get(
        metadata: BrowserFileMetadata,
        needDuration: Boolean = false,
        needTags: Boolean = false,
        artwork: MediaArtworkRequest? = null
    ): CachedMediaFields<T, A> {
        val key = Key(metadata.path, metadata.cacheKey)
        val entry = acquireEntry(key)
        try {
            return synchronized(entry) {
                val cachedArtwork = synchronized(lock) { artwork?.let { artworks[ArtworkKey(key, it)] } }
                val cachedCover = synchronized(lock) { if (artwork != null && cachedArtwork == null) covers[key] else null }
                val missing = MissingMediaFields(
                    duration = needDuration && !entry.durationLoaded,
                    tags = needTags && !entry.tagsLoaded,
                    cover = artwork != null && cachedArtwork == null && cachedCover == null,
                    artwork = artwork?.takeIf { cachedArtwork == null }
                )
                var decodedArtwork = cachedArtwork?.value
                if (missing.duration || missing.tags || missing.cover || missing.artwork != null) {
                    permits.acquire()
                    val loaded = try { load(metadata, missing, cachedCover?.value) } finally { permits.release() }
                    if (missing.duration) {
                        entry.durationMs = loaded.durationMs.coerceAtLeast(0L)
                        entry.durationLoaded = true
                    }
                    if (missing.tags) {
                        entry.tags = loaded.tags ?: emptyTags
                        entry.tagsLoaded = true
                    }
                    if (missing.artwork != null) decodedArtwork = loaded.artwork
                    synchronized(lock) {
                        // Invalidated work may finish for its original caller, but cannot
                        // recreate a cache entry or overwrite a replacement request.
                        if (entries[key] === entry) {
                            if (missing.cover) putCover(key, loaded.cover)
                            missing.artwork?.let { putArtwork(ArtworkKey(key, it), loaded.artwork) }
                        }
                    }
                }
                CachedMediaFields(entry.durationMs, entry.tags, decodedArtwork)
            }
        } finally {
            releaseEntry(entry)
        }
    }

    fun invalidate(path: String) = synchronized(lock) {
        entries.keys.removeAll { it.path == path }
        val coverIterator = covers.iterator()
        while (coverIterator.hasNext()) {
            val item = coverIterator.next()
            if (item.key.path == path) {
                coverBytes -= item.value.value?.size ?: 1
                coverIterator.remove()
            }
        }
        val artworkIterator = artworks.iterator()
        while (artworkIterator.hasNext()) {
            val item = artworkIterator.next()
            if (item.key.file.path == path) {
                artworkBytes -= item.value.value?.let(artworkWeight)?.coerceAtLeast(1) ?: 1
                artworkIterator.remove()
            }
        }
    }

    fun details(metadata: BrowserFileMetadata, loadDetails: () -> T?): T? {
        val key = Key(metadata.path, metadata.cacheKey)
        val entry = acquireEntry(key)
        try {
            return synchronized(entry) {
                if (!entry.detailsLoaded) {
                    permits.acquire()
                    entry.details = try { loadDetails() } finally { permits.release() }
                    entry.detailsLoaded = true
                }
                entry.details
            }
        } finally {
            releaseEntry(entry)
        }
    }

    /** A validated persistent library row can supply duration without reopening the source. */
    fun seedDuration(metadata: BrowserFileMetadata, durationMs: Long) {
        val key = Key(metadata.path, metadata.cacheKey)
        val entry = acquireEntry(key)
        try {
            synchronized(entry) {
                if (!entry.durationLoaded) {
                    entry.durationMs = durationMs.coerceAtLeast(0L)
                    entry.durationLoaded = true
                }
            }
        } finally {
            releaseEntry(entry)
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        covers.clear()
        artworks.clear()
        coverBytes = 0L
        artworkBytes = 0L
    }

    private fun trimEntries() {
        val iterator = entries.iterator()
        while (entries.size > maximumEntries && iterator.hasNext()) {
            if (iterator.next().value.users == 0) iterator.remove()
        }
    }

    private fun acquireEntry(key: Key): Entry<T> = synchronized(lock) {
        val entry = entries[key] ?: Entry(emptyTags).also { entries[key] = it }
        entry.users++
        trimEntries()
        entry
    }

    private fun releaseEntry(entry: Entry<T>) = synchronized(lock) {
        entry.users--
        trimEntries()
    }

    private fun putCover(key: Key, cover: ByteArray?) {
        covers.put(key, NullableValue(cover))?.let { coverBytes -= it.value?.size ?: 1 }
        coverBytes += cover?.size ?: 1
        val iterator = covers.iterator()
        while ((coverBytes > maximumCoverBytes || covers.size > maximumEntries) && iterator.hasNext()) {
            coverBytes -= iterator.next().value.value?.size ?: 1
            iterator.remove()
        }
    }

    private fun putArtwork(key: ArtworkKey, artwork: A?) {
        artworks.put(key, NullableValue(artwork))?.let {
            artworkBytes -= it.value?.let(artworkWeight)?.coerceAtLeast(1) ?: 1
        }
        artworkBytes += artwork?.let(artworkWeight)?.coerceAtLeast(1) ?: 1
        val iterator = artworks.iterator()
        while ((artworkBytes > maximumArtworkBytes || artworks.size > maximumEntries) && iterator.hasNext()) {
            artworkBytes -= iterator.next().value.value?.let(artworkWeight)?.coerceAtLeast(1) ?: 1
            iterator.remove()
        }
    }
}
