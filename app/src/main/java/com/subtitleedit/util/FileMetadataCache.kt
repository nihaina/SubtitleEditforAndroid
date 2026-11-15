package com.subtitleedit.util

import com.subtitleedit.model.BrowserFileMetadata
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class FileMetadataInvalidation(val path: String, val recursive: Boolean)

/** One path owns one file version, regardless of which screen requests it. IO stays on callers' workers. */
internal class FileMetadataCache(
    private val reader: (File, Long) -> BrowserFileMetadata? = BrowserFileMetadata::read,
    private val maxEntries: Int = 8192
) {
    private class Entry {
        val readLock = Any()
        var value: BrowserFileMetadata? = null
        var initialized = false
        var validate = false
        var forceRevision = false
        var invalidation = 0L
        var users = 0
    }

    private data class Invalidation(val path: String, val recursive: Boolean, val forceRevision: Boolean)
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val invalidations = ConcurrentLinkedQueue<Invalidation>()
    private val changes = AtomicLong()
    val generation: Long get() = changes.get()
    private val dirtyEvents = MutableSharedFlow<FileMetadataInvalidation>(
        replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val invalidatedPaths: SharedFlow<FileMetadataInvalidation> = dirtyEvents.asSharedFlow()
    private val dirtyRevision = MutableStateFlow(0L)
    /** This conflated counter is the lossless wake-up signal during event bursts. */
    val invalidationRevision: StateFlow<Long> = dirtyRevision.asStateFlow()

    init { require(maxEntries > 0) }

    fun get(file: File): BrowserFileMetadata? = read(file, refresh = false, forceRevision = false)

    /** Rechecking an unchanged file must not invalidate its previously decoded media. */
    fun refresh(file: File, forceRevision: Boolean = false): BrowserFileMetadata? {
        if (forceRevision) {
            invalidate(file.absolutePath)
            return get(file)
        }
        return read(file, refresh = true, forceRevision = false)
    }

    fun peek(path: String): BrowserFileMetadata? = synchronized(lock) {
        drainInvalidations()
        entries[path]?.takeIf { it.initialized && !it.validate }?.value
    }

    /** Actual writes also change the version when a writer preserves size and mtime. */
    fun invalidate(path: String, recursive: Boolean = false) {
        invalidations.add(Invalidation(path, recursive, true))
        changes.incrementAndGet()
        signalInvalidation(path, recursive)
    }

    /** An entry event can also invalidate cached descendant listings; only the entry is forced. */
    fun invalidateEntryAndValidateDescendants(path: String) {
        invalidations.add(Invalidation(path, recursive = true, forceRevision = false))
        invalidations.add(Invalidation(path, recursive = false, forceRevision = true))
        changes.incrementAndGet()
        signalInvalidation(path, recursive = true)
    }

    /** Observation gaps need verification, not unconditional invalidation of decoded fields. */
    fun requireValidation(path: String, recursive: Boolean = false) {
        invalidations.add(Invalidation(path, recursive, false))
        changes.incrementAndGet()
        signalInvalidation(path, recursive)
    }

    fun clear() {
        synchronized(lock) {
            invalidations.clear()
            entries.values.forEach { it.invalidation++; it.validate = true; it.forceRevision = true }
            entries.clear()
            changes.incrementAndGet()
        }
        signalInvalidation("", recursive = true)
    }

    private fun signalInvalidation(path: String, recursive: Boolean) {
        dirtyEvents.tryEmit(FileMetadataInvalidation(path, recursive))
        // StateFlow always retains the newest sequence, even if a slow consumer
        // missed individual path events. get() remains a no-IO hit for other paths.
        synchronized(dirtyRevision) { dirtyRevision.value = dirtyRevision.value + 1L }
    }

    private fun read(file: File, refresh: Boolean, forceRevision: Boolean): BrowserFileMetadata? {
        val path = file.absolutePath
        val entry = synchronized(lock) {
            drainInvalidations()
            entries.getOrPut(path) { Entry() }.also { it.users++ }
        }
        try {
            synchronized(entry.readLock) {
                var needsRefresh = refresh
                while (true) {
                    val pending = synchronized(lock) {
                        drainInvalidations()
                        if (!needsRefresh && entry.initialized && !entry.validate) return entry.value
                        Triple(entry.value, entry.invalidation, forceRevision || entry.forceRevision)
                    }
                    val loaded = reader(file, BrowserFileMetadata.nextRevision())
                    synchronized(lock) {
                        // Invalidations never wait behind a blocked stat. Recheck them before
                        // publishing, otherwise a pre-event read could hide the event forever.
                        drainInvalidations()
                        if (entry.invalidation == pending.second) {
                            val previous = pending.first
                            val value = if (!pending.third && previous != null && loaded != null &&
                                sameAttributes(previous, loaded)) previous else loaded
                            if (entry.initialized && value !== entry.value) changes.incrementAndGet()
                            entry.value = value
                            entry.initialized = true
                            entry.validate = false
                            entry.forceRevision = false
                            return value
                        }
                    }
                    needsRefresh = true
                }
            }
        } finally {
            synchronized(lock) {
                entry.users--
                trim()
            }
        }
    }

    private fun drainInvalidations() {
        while (true) {
            val dirty = invalidations.poll() ?: return
            entries.forEach { (path, entry) ->
                if (path == dirty.path || dirty.recursive && isFilePathWithin(path, dirty.path)) {
                    entry.invalidation++
                    entry.validate = true
                    entry.forceRevision = entry.forceRevision || dirty.forceRevision
                }
            }
        }
    }

    private fun trim() {
        if (entries.size <= maxEntries) return
        val iterator = entries.iterator()
        while (entries.size > maxEntries && iterator.hasNext()) {
            if (iterator.next().value.users == 0) iterator.remove()
        }
    }

    private fun sameAttributes(left: BrowserFileMetadata, right: BrowserFileMetadata): Boolean =
        left.isDirectory == right.isDirectory && left.isRegularFile == right.isRegularFile &&
            left.size == right.size && left.modifiedTimeMillis == right.modifiedTimeMillis &&
            left.restricted == right.restricted
}

internal fun isFilePathWithin(path: String, root: String): Boolean =
    path == root || path.startsWith(root.trimEnd('/', '\\') + File.separator)
