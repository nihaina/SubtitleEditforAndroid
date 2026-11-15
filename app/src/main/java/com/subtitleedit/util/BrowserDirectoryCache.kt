package com.subtitleedit.util

import com.subtitleedit.model.BrowserFileMetadata
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

internal data class BrowserDirectorySnapshot(
    val directory: File,
    val entries: List<BrowserFileMetadata>,
    val parent: BrowserFileMetadata? = null,
    val canonicalPath: String = directory.absolutePath
)

/**
 * Owns directory snapshots while changes are observed. IO happens only on misses,
 * explicitly changed entries, or reconciliation after an observation gap.
 * Call loading methods from a worker dispatcher.
 */
internal class BrowserDirectoryCache(
    private val metadataReader: (File, Long) -> BrowserFileMetadata? = BrowserFileMetadata::read,
    private val listFiles: (File) -> List<File> = { it.listFiles()?.toList().orEmpty() },
    private val maxDirectories: Int = 12,
    private val maxMetadataEntries: Int = 8192
) {
    private data class CachedDirectory(
        val snapshot: BrowserDirectorySnapshot,
        val byPath: Map<String, BrowserFileMetadata> = snapshot.entries.associateBy { it.path },
        var valid: Boolean = true
    )

    private val directories = LinkedHashMap<String, CachedDirectory>(16, 0.75f, true)
    private val metadataByPath = LinkedHashMap<String, BrowserFileMetadata>(16, 0.75f, true)
    private val pendingInvalidations = ConcurrentLinkedQueue<String>()

    init {
        require(maxDirectories > 0)
        require(maxMetadataEntries > 0)
    }

    @Synchronized
    fun load(directory: File): BrowserDirectorySnapshot {
        drainInvalidations()
        val path = directory.absolutePath
        directories[path]?.takeIf { it.valid }?.let { return it.snapshot }
        // Full reconciliation deliberately gives fresh revisions even if size and
        // mtime match: content may have changed while the observer was stopped.
        removeMetadataBelow(path)
        val entries = listFiles(directory).distinctBy { it.absolutePath }.mapNotNull(::read)
        val parent = directory.parentFile?.takeIf { it.canRead() }?.let {
            BrowserFileMetadata(
                file = File(directory.absolutePath + "/.."),
                isDirectory = true,
                size = 0L,
                modifiedTimeMillis = 0L,
                revision = BrowserFileMetadata.nextRevision()
            )
        }
        val canonicalPath = runCatching { directory.canonicalPath }.getOrDefault(path)
        return BrowserDirectorySnapshot(directory, entries, parent, canonicalPath).also { save(it) }
    }

    @Synchronized
    fun update(
        directory: File,
        changedNames: Set<String>,
        countChangedDirectories: Set<String> = emptySet(),
        fullRefresh: Boolean = false
    ): BrowserDirectorySnapshot {
        drainInvalidations()
        val path = directory.absolutePath
        val cached = directories[path]
        if (fullRefresh || cached == null || !cached.valid) {
            if (fullRefresh) invalidateNow(path)
            return load(directory)
        }
        if (changedNames.isEmpty() && countChangedDirectories.isEmpty()) return cached.snapshot

        val entries = cached.snapshot.entries.associateByTo(LinkedHashMap()) { it.path }
        val refreshedPaths = mutableSetOf<String>()
        for (name in changedNames) {
            // FileObserver paths must identify an immediate entry, never escape
            // the watched directory or cause an unrelated subtree to be read.
            if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name) continue
            val file = File(directory, name)
            val changedPath = file.absolutePath
            refreshedPaths += changedPath
            val previous = entries[changedPath]
            val updated = read(file)
            if (updated == null) {
                entries.remove(changedPath)
                removeTree(changedPath)
            } else {
                if (updated.isDirectory || previous?.isDirectory == true) invalidateNow(changedPath)
                entries[changedPath] = updated
                remember(updated)
            }
        }
        for (changedPath in countChangedDirectories) {
            // Counts can change at the same time as a named directory event.
            // Its subtree must become stale in either case, without a duplicate stat.
            invalidateNow(changedPath)
            if (changedPath in refreshedPaths) {
                entries[changedPath]?.let(::remember)
                continue
            }
            val metadata = entries[changedPath] ?: continue
            if (metadata.isDirectory) {
                // Adding/removing a child also changes the directory mtime.
                // Refresh just this folder, never its unaffected siblings.
                val updated = read(metadata.file)
                if (updated != null) entries[changedPath] = updated else entries.remove(changedPath)
            }
        }
        return cached.snapshot.copy(entries = entries.values.toList()).also { save(it) }
    }

    fun invalidate(directoryPath: String) {
        // Called from UI lifecycle callbacks, including destruction. Never wait
        // behind worker IO or delegate this marker to a cancellable coroutine.
        pendingInvalidations.add(directoryPath)
    }

    private fun invalidateNow(directoryPath: String) {
        directories.forEach { (path, cached) ->
            if (isWithin(path, directoryPath)) cached.valid = false
        }
        removeMetadataBelow(directoryPath)
    }

    @Synchronized
    fun metadata(files: List<File>): Map<String, BrowserFileMetadata> {
        drainInvalidations()
        return buildMap {
            files.forEach { file ->
                val metadata = cachedMetadata(file.absolutePath) ?: read(file)
                if (metadata != null) put(file.absolutePath, metadata)
            }
        }
    }

    @Synchronized
    fun cachedMetadata(path: String): BrowserFileMetadata? {
        drainInvalidations()
        metadataByPath[path]?.let { return it }
        // A large active directory may exceed the auxiliary metadata LRU. Its
        // snapshot still owns every row, so eviction must not cause another stat.
        return directories.values.asSequence().filter { it.valid }
            .mapNotNull { cached -> cached.byPath[path] }
            .firstOrNull()?.also(::remember)
    }

    private fun read(file: File): BrowserFileMetadata? =
        metadataReader(file, BrowserFileMetadata.nextRevision())?.also(::remember)

    private fun drainInvalidations() {
        while (true) invalidateNow(pendingInvalidations.poll() ?: return)
    }

    private fun remember(metadata: BrowserFileMetadata) {
        metadataByPath[metadata.path] = metadata
        while (metadataByPath.size > maxMetadataEntries) metadataByPath.remove(metadataByPath.keys.first())
    }

    private fun save(snapshot: BrowserDirectorySnapshot) {
        directories[snapshot.directory.absolutePath] = CachedDirectory(snapshot)
        while (directories.size > maxDirectories) directories.remove(directories.keys.first())
    }

    private fun removeTree(path: String) {
        directories.keys.removeAll { isWithin(it, path) }
        removeMetadataBelow(path)
    }

    private fun removeMetadataBelow(directoryPath: String) {
        metadataByPath.keys.removeAll { isWithin(it, directoryPath) }
    }

    private fun isWithin(path: String, directoryPath: String): Boolean =
        path == directoryPath || path.startsWith(directoryPath.trimEnd('/', '\\') + File.separator)

}
