package com.subtitleedit.util

import com.subtitleedit.model.BrowserFileMetadata
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

internal data class BrowserDirectorySnapshot(
    val directory: File,
    val entries: List<BrowserFileMetadata>,
    val parent: BrowserFileMetadata? = null,
    val canonicalPath: String = directory.absolutePath,
    val listingSucceeded: Boolean = true
)

/** Directory membership and shared attributes are refreshed independently, only on worker threads. */
internal class BrowserDirectoryCache(
    val attributes: FileMetadataCache = FileMetadataCache(),
    private val listFiles: (File) -> List<File>? = { it.listFiles()?.toList() },
    private val maxDirectories: Int = 12
) {
    private class Entry {
        val readLock = Any()
        var snapshot: BrowserDirectorySnapshot? = null
        var listing: List<File>? = null
        var listingValid = false
        var valid = false
        var invalidation = 0L
        var attributeGeneration = -1L
        var users = 0
    }

    private val lock = Any()
    private val directories = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val pendingInvalidations = ConcurrentLinkedQueue<String>()

    init { require(maxDirectories > 0) }

    fun load(directory: File): BrowserDirectorySnapshot = withDirectory(directory) { entry ->
        while (true) {
            val state = synchronized(lock) {
                drainInvalidations()
                Triple(entry.snapshot, entry.valid && entry.listingValid, entry.invalidation)
            }
            val previous = state.first
            if (state.second && previous != null) {
                // Another consumer may have refreshed a file without rendering this directory.
                // Unchanged generations are O(1); changed generations only consult shared hits.
                if (entry.attributeGeneration == attributes.generation) return@withDirectory previous
                val entries = readEntries(previous.entries.map { it.file })
                val snapshot = if (entries == previous.entries) previous else previous.copy(entries = entries)
                synchronized(lock) {
                    drainInvalidations()
                    entry.snapshot = snapshot
                    entry.attributeGeneration = attributes.generation
                    // The owner will ignore a completion from a stopped watch.
                    // Leave a gap dirty rather than rescan an obsolete directory.
                    return@withDirectory snapshot
                }
            }

            val listing = if (entry.listingValid) entry.listing else listFiles(directory)?.distinctBy { it.absolutePath }
            val children = listing.orEmpty()
            val entries = if (listing == null) previous?.entries.orEmpty() else readEntries(children)
            val childPaths = children.mapTo(HashSet()) { it.absolutePath }
            if (listing != null) {
                previous?.entries?.filter { it.path !in childPaths }?.forEach {
                    attributes.invalidate(it.path, recursive = it.isDirectory)
                }
            }
            val parent = directory.parentFile?.takeIf { it.canRead() }?.let {
                previous?.parent ?: BrowserFileMetadata(
                    file = File(directory.absolutePath + "/.."), isDirectory = true,
                    size = 0L, modifiedTimeMillis = 0L, revision = BrowserFileMetadata.nextRevision()
                )
            }
            val canonicalPath = runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
            val snapshot = BrowserDirectorySnapshot(directory, entries, parent, canonicalPath, listing != null)
            synchronized(lock) {
                drainInvalidations()
                entry.snapshot = snapshot
                entry.listing = listing
                entry.listingValid = entry.invalidation == state.third
                entry.valid = entry.listingValid
                entry.attributeGeneration = attributes.generation
                return@withDirectory snapshot
            }
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    /** Counting a folder needs its names, never attributes or media metadata for every child. */
    fun itemCount(directory: File): Int = withDirectory(directory) { entry ->
        while (true) {
            val generation = synchronized(lock) {
                drainInvalidations()
                if (entry.listingValid) return@withDirectory entry.listing?.size ?: -1
                entry.invalidation
            }
            val listing = listFiles(directory)?.distinctBy { it.absolutePath }
            synchronized(lock) {
                drainInvalidations()
                if (entry.invalidation == generation) {
                    entry.listing = listing
                    entry.listingValid = true
                    return@withDirectory listing?.size ?: -1
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    fun update(
        directory: File,
        changedNames: Set<String>,
        countChangedDirectories: Set<String> = emptySet(),
        fullRefresh: Boolean = false
    ): BrowserDirectorySnapshot = withDirectory(directory) { entry ->
        synchronized(lock) { drainInvalidations() }
        val changedFiles = changedNames.asSequence().filter {
            it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it
        }.map { File(directory, it) }.associateBy { it.absolutePath }
        val countedPaths = countChangedDirectories.filterTo(linkedSetOf()) {
            val file = File(it)
            file.parentFile?.absolutePath == directory.absolutePath && file.name != "." && file.name != ".."
        }
        // A directory snapshot can have been evicted while shared attributes and
        // decoded media remain cached. Honor real writes before the early load path.
        val affectedPaths = changedFiles.keys + countedPaths
        affectedPaths.forEach { path ->
            invalidateMembership(path)
            attributes.invalidateEntryAndValidateDescendants(path)
        }
        if (fullRefresh) {
            attributes.invalidate(directory.absolutePath, recursive = true)
            invalidateMembership(directory.absolutePath)
        }
        if (!entry.valid || entry.snapshot == null) {
            if (affectedPaths.isNotEmpty()) synchronized(lock) {
                entry.listingValid = false
                entry.invalidation++
            }
            return@withDirectory load(directory)
        }
        val previous = entry.snapshot!!
        val byPath = previous.entries.associateByTo(LinkedHashMap()) { it.path }
        val listedByPath = entry.listing.orEmpty().associateByTo(LinkedHashMap()) { it.absolutePath }
        val refreshed = mutableSetOf<String>()
        for ((_, file) in changedFiles) {
            val loaded = attributes.get(file)
            refreshed += file.absolutePath
            if (loaded == null) {
                byPath.remove(file.absolutePath)
                listedByPath.remove(file.absolutePath)
            } else {
                byPath[file.absolutePath] = loaded
                listedByPath[file.absolutePath] = file
            }
        }
        for (path in countedPaths) {
            if (path in refreshed) continue
            val old = byPath[path]?.takeIf { it.isDirectory } ?: continue
            val loaded = attributes.get(old.file)
            if (loaded == null) byPath.remove(path) else byPath[path] = loaded
        }
        previous.copy(entries = byPath.values.toList()).also { snapshot ->
            synchronized(lock) {
                entry.snapshot = snapshot
                    entry.listing = listedByPath.values.toList().takeIf { snapshot.listingSucceeded }
                entry.attributeGeneration = attributes.generation
            }
        }
    }

    /** No waiting behind a scan: lifecycle owners can mark a gap even while being destroyed. */
    fun invalidate(directoryPath: String) {
        attributes.requireValidation(directoryPath, recursive = true)
        pendingInvalidations.add(directoryPath)
    }

    private fun invalidateMembership(path: String) = synchronized(lock) {
        directories.forEach { (cachedPath, entry) ->
            if (isFilePathWithin(cachedPath, path)) {
                entry.valid = false
                entry.listingValid = false
                entry.invalidation++
            }
        }
    }

    private fun drainInvalidations() {
        while (true) invalidateMembership(pendingInvalidations.poll() ?: return)
    }

    private fun readEntries(files: List<File>): List<BrowserFileMetadata> {
        while (true) {
            val generation = attributes.generation
            val entries = files.mapNotNull { attributes.get(it) }
            // A different consumer can invalidate an earlier child while this scan
            // is reading a later child. Revisit shared hits before declaring it current.
            if (attributes.generation == generation) return entries
        }
    }

    private fun <T> withDirectory(directory: File, block: (Entry) -> T): T {
        val entry = synchronized(lock) {
            drainInvalidations()
            directories.getOrPut(directory.absolutePath) { Entry() }.also { it.users++ }
        }
        try { return synchronized(entry.readLock) { block(entry) } }
        finally {
            synchronized(lock) {
                entry.users--
                val iterator = directories.iterator()
                while (directories.size > maxDirectories && iterator.hasNext()) {
                    if (iterator.next().value.users == 0) iterator.remove()
                }
            }
        }
    }
}
