package com.subtitleedit.library

import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.subtitleedit.util.SharedFileCache
import com.subtitleedit.util.DirectoryUpdateTracker
import com.subtitleedit.util.FavoriteDirectoryStore
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-owned recursive subscriptions. Page changes do not tear down library observation. */
internal object MediaLibraryFileObserver {
    private val coverage = MutableStateFlow<Set<String>>(emptySet())
    val observedDirectories = coverage.asStateFlow()
    private val _indexing = MutableStateFlow(false)
    val indexing = _indexing.asStateFlow()
    val directoryUpdates = DirectoryUpdateTracker()
    @Volatile private var instance: MediaLibraryObservation? = null
    private fun observation(context: Context): MediaLibraryObservation = instance ?: synchronized(this) {
        val app = context.applicationContext
        instance ?: MediaLibraryObservation(MediaLibraryRepository.getInstance(app),
            onCoverageChanged = { paths ->
                directoryUpdates.retain(paths + paths.mapNotNull { File(it).parentFile?.absolutePath })
                coverage.value = paths
            },
            onDirectoriesUpdated = directoryUpdates::publish,
            configuredRoots = { FavoriteDirectoryStore(app).load().mediaLibraryPaths },
            onIndexingChanged = { _indexing.value = it }).also { instance = it }
    }

    fun syncRoots(context: Context, directories: List<File>) = observation(context).syncRoots(context, directories)
    fun removeDirectory(context: Context, directory: File, retainedDirectories: List<File>) =
        observation(context).removeDirectory(context, directory, retainedDirectories)
    suspend fun reconcile(context: Context, directories: List<File>): Int = observation(context).reconcile(context, directories)
}

/** An isolated observer can be exercised with a disposable database in instrumentation. */
internal class MediaLibraryObservation(
    private val repository: MediaLibraryRepository,
    private val onCoverageChanged: (Set<String>) -> Unit = {},
    private val onDirectoriesUpdated: (Set<String>) -> Unit = {},
    private val configuredRoots: (() -> Set<String>)? = null,
    private val onIndexingChanged: (Boolean) -> Unit = {}
) {
    private sealed interface Request {
        data class Roots(val context: Context, val paths: Set<String>, val version: Long,
            val removedPaths: Set<String> = emptySet()) : Request
        data class RemoveDirectory(val context: Context, val path: String, val retainedPaths: Set<String>, val version: Long) : Request
        data class Reconcile(val context: Context, val directories: List<File>, val result: CompletableDeferred<Int>, val version: Long) : Request
        data class Changes(val directory: String, val token: Long, val paths: Set<String>,
            val reset: Boolean, val membershipChanged: Boolean, val version: Long) : Request
        data class Stop(val finished: CompletableDeferred<Unit>) : Request
    }
    private data class Watch(val token: Long, val observer: FileObserver, val canonicalPath: String)
    private data class Pending(val paths: MutableSet<String> = linkedSetOf(), var reset: Boolean = false,
        var membershipChanged: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val enqueueLock = Any()
    private val tokenSource = AtomicLong()
    private val scanVersion = AtomicLong()
    private val eventThread = HandlerThread("MediaLibraryFileEvents").apply { start() }
    private val eventHandler = Handler(eventThread.looper)
    private val watches = linkedMapOf<String, Watch>()
    private val canonicalWatches = hashMapOf<String, String>()
    private val pending = linkedMapOf<Pair<String, Long>, Pending>()
    private var flushScheduled = false
    private var roots = emptySet<String>()
    private var retainedCatalogRoots: Set<String>? = null
    private var publishedCoverage = emptySet<String>()
    private var coverageDirty = false
    private val readyRoots = linkedSetOf<String>()
    private var appContext: Context? = null

    init {
        scope.launch {
            for (request in requests) {
                try {
                    when (request) {
                        is Request.Roots -> if (request.version == scanVersion.get()) changeRoots(request)
                        is Request.RemoveDirectory -> changeRoots(Request.Roots(request.context,
                            roots.intersect(request.retainedPaths), request.version, setOf(request.path)), request.retainedPaths)
                        is Request.Reconcile -> {
                            checkScanVersion(request.version)
                            appContext = request.context
                            // A picker scan may be queued after its directory has already been removed.
                            val configured = configuredRootPaths()
                            if (configured != null) {
                                changeRoots(Request.Roots(request.context, configured, request.version))
                            }
                            val directories = request.directories.filter { configured == null || it.absolutePath in configured }
                            if (request.directories.isNotEmpty() && directories.isEmpty()) {
                                throw CancellationException("Scan directories were removed")
                            }
                            val added = directories.map { it.absolutePath }.filter { it !in roots }
                            roots = roots + added
                            if (added.isNotEmpty()) coverageDirty = true
                            added.mapNotNull { File(it).parentFile }.forEach(::observe)
                            // Selecting a just-added directory also updates preferences.
                            // Root registration and that explicit request share one reconciliation.
                            val count = if (directories.all { it.absolutePath in readyRoots }) {
                                repository.getIndexedTracks().count { track ->
                                    directories.any { within(track.path, it.absolutePath) }
                                }
                            } else reconcileNow(request.context, directories, request.version)
                            checkScanVersion(request.version)
                            publishCoverage()
                            request.result.complete(count)
                        }
                        is Request.Changes -> if (request.version == scanVersion.get()) applyChanges(request)
                            else invalidateReadyRoots(request.paths)
                        is Request.Stop -> {
                            removeWatches { true }
                            publishCoverage()
                            eventHandler.removeCallbacksAndMessages(null)
                            eventThread.quitSafely()
                            request.finished.complete(Unit)
                            requests.close()
                            break
                        }
                    }
                } catch (error: Exception) {
                    if (request is Request.Reconcile) request.result.completeExceptionally(error)
                    else if (error !is CancellationException) Log.w("MediaLibrary", "Incremental library refresh failed", error)
                } finally {
                    // Native watches are installed before each listing. Publish their ownership
                    // at request boundaries, including cancelled scans, instead of copying N growing sets.
                    publishCoverage()
                }
            }
        }
    }

    /** Idempotent; recomposing or reopening a page with the same roots performs no IO. */
    fun syncRoots(context: Context, directories: List<File>) {
        enqueue { version -> Request.Roots(context.applicationContext,
            directories.mapTo(linkedSetOf()) { it.absolutePath }, version) }
    }

    /** Removal is serialized with scans and events, including before this process has observed any roots. */
    fun removeDirectory(context: Context, directory: File, retainedDirectories: List<File>) {
        // The worker may be inside a long scan, so cancellation cannot wait for the removal message.
        synchronized(enqueueLock) {
            val version = scanVersion.incrementAndGet()
            requests.trySend(Request.RemoveDirectory(context.applicationContext, directory.absolutePath,
                retainedDirectories.mapTo(linkedSetOf()) { it.absolutePath }, version))
        }
    }

    suspend fun reconcile(context: Context, directories: List<File>): Int {
        val result = CompletableDeferred<Int>()
        enqueue { version -> Request.Reconcile(context.applicationContext, directories, result, version) }.getOrThrow()
        return result.await()
    }

    /** Version capture and enqueue must stay ordered with removals, including on the event thread. */
    private fun enqueue(request: (Long) -> Request) = synchronized(enqueueLock) {
        requests.trySend(request(scanVersion.get()))
    }

    suspend fun stop() {
        val finished = CompletableDeferred<Unit>()
        requests.send(Request.Stop(finished))
        finished.await()
        scope.cancel()
    }

    private fun changeRoots(request: Request.Roots, retainedPaths: Set<String> = request.paths) {
        appContext = request.context
        val configured = configuredRootPaths()
        val requestedPaths = configured ?: request.paths
        if (roots == requestedPaths && request.removedPaths.isEmpty()) return
        val added = requestedPaths - roots
        roots = requestedPaths
        coverageDirty = true
        readyRoots.retainAll(roots)
        removeWatches { path -> roots.none { within(path, it) || File(it).parentFile?.absolutePath == path } }
        val retainedDirectories = (configured ?: retainedPaths).map(::File)
        // Without an authoritative configuration, an empty navigation snapshot
        // alone is not a removal. Configured roots are pruned separately above.
        request.removedPaths.forEach { repository.removeDirectoryFromLibrary(File(it), retainedDirectories) }
        added.mapNotNull { File(it).parentFile }.forEach(::observe)
        val unfinished = if (request.removedPaths.isNotEmpty()) roots - readyRoots else added
        if (unfinished.isNotEmpty() && request.version == scanVersion.get()) {
            reconcileNow(request.context, unfinished.map(::File), request.version)
        }
    }

    /** Preferences, when supplied, distinguish library roots from ordinary directory shortcuts. */
    private fun configuredRootPaths(): Set<String>? {
        val configured = configuredRoots?.invoke() ?: return null
        if (configured != retainedCatalogRoots) {
            repository.retainLibraryDirectories(configured.map(::File))
            retainedCatalogRoots = configured.toSet()
        }
        return configured
    }

    private fun checkScanVersion(version: Long) {
        if (version != scanVersion.get() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Media scan superseded by directory removal")
        }
    }

    private fun reconcileNow(context: Context, directories: List<File>, version: Long): Int {
        checkScanVersion(version)
        val previouslyReady = invalidateReadyRoots(directories.map { it.absolutePath })
        return whileIndexing {
            reconcileDirectories(context, directories, version).also { readyRoots.addAll(previouslyReady) }
        }
    }

    private inline fun <T> whileIndexing(block: () -> T): T {
        onIndexingChanged(true)
        return try { block() } finally { onIndexingChanged(false) }
    }

    private fun invalidateReadyRoots(paths: Collection<String>): List<String> =
        readyRoots.filter { root -> paths.any { within(root, it) || within(it, root) } }
            .also { readyRoots.removeAll(it.toSet()) }

    private fun reconcileDirectories(context: Context, directories: List<File>, version: Long): Int {
        directories.forEach { SharedFileCache.directories.invalidate(it.absolutePath) }
        val seen = linkedSetOf<String>()
        val successfulListings = linkedSetOf<String>()
        val count = repository.scanDirectoriesToLibrary(
            directories,
            isCancelled = { version != scanVersion.get() || Thread.currentThread().isInterrupted },
            onDirectoryDiscovered = { directory ->
                seen += directory.absolutePath
                if (roots.any { within(directory.absolutePath, it) }) observe(directory)
            },
            onDirectoryScanned = { directory, _ ->
                seen += directory.absolutePath
                successfulListings += directory.absolutePath
                if (roots.any { within(directory.absolutePath, it) }) observe(directory)
            }
        )
        checkScanVersion(version)
        removeWatches { path -> directories.any { within(path, it.absolutePath) } && path !in seen &&
            roots.none { File(it).parentFile?.absolutePath == path } }
        directories.map { it.absolutePath }.filter { it in successfulListings }.forEach(readyRoots::add)
        onDirectoriesUpdated((seen + directories.map { it.absolutePath }).withParents())
        return count
    }

    private fun applyChanges(request: Request.Changes) {
        val context = appContext ?: return
        configuredRoots?.invoke()?.takeIf { it != roots }?.let {
            changeRoots(Request.Roots(context, it, request.version))
        }
        if (watches[request.directory]?.token != request.token) return
        var paths = request.paths.filterTo(linkedSetOf()) { path -> roots.any { within(path, it) } }
        if (request.reset) {
            removeWatches { within(it, request.directory) }
            SharedFileCache.directories.invalidate(request.directory)
        }
        if (paths.isEmpty()) return
        if (!request.reset) {
            // A named file event updates that entry, never validates every sibling.
            SharedFileCache.directories.update(File(request.directory), paths.mapTo(linkedSetOf()) { File(it).name })
        }
        if (request.membershipChanged) {
            // A direct child add/remove changes the displayed directory's date/count.
            // Keep its freshly updated raw listing; only its own attributes need a new version.
            SharedFileCache.attributes.refresh(File(request.directory), forceRevision = true)
        }
        paths = paths.filterTo(linkedSetOf()) { path ->
            val file = SharedFileCache.attributes.get(File(path))
            // A symlink back into an already subscribed tree is not a second library.
            val canonical = if (file?.isDirectory == true) runCatching { File(path).canonicalPath }.getOrNull() else null
            canonical == null || canonicalWatches[canonical].let { it == null || it == path }
        }
        if (paths.isEmpty()) {
            onDirectoriesUpdated(setOf(request.directory).withParents())
            return
        }
        val previouslyReady = invalidateReadyRoots(paths)
        whileIndexing {
            repository.updateObservedPaths(paths,
                metadataAlreadyUpdated = !request.reset,
                isCancelled = { request.version != scanVersion.get() || Thread.currentThread().isInterrupted },
                onDirectoryDiscovered = ::observe) { directory, _ ->
                if (roots.any { within(directory.absolutePath, it) }) observe(directory)
            }
            checkScanVersion(request.version)
            readyRoots.addAll(previouslyReady)
        }
        paths.forEach { path ->
            val metadata = SharedFileCache.attributes.get(File(path))
            if (metadata?.isDirectory != true) removeWatches { within(it, path) }
        }
        onDirectoriesUpdated(setOf(request.directory).withParents())
    }

    @Suppress("DEPRECATION")
    private fun observe(directory: File) {
        val path = directory.absolutePath
        if (path in watches) return
        val canonicalPath = runCatching { directory.canonicalPath }.getOrDefault(path)
        if (canonicalPath in canonicalWatches) return
        val token = tokenSource.incrementAndGet()
        val receive: (Int, String?) -> Unit = { event, name ->
            eventHandler.post {
                val item = pending.getOrPut(path to token, ::Pending)
                item.membershipChanged = item.membershipChanged || event and MEMBERSHIP_EVENTS != 0
                if (name == null || event and SELF_EVENTS != 0) {
                    item.paths += path
                    item.reset = true
                } else if (!name.startsWith('.')) item.paths += File(path, name).absolutePath
                if (!flushScheduled) {
                    flushScheduled = true
                    eventHandler.postDelayed({
                        flushScheduled = false
                        val batches = pending.toMap()
                        pending.clear()
                        batches.forEach { (source, change) ->
                            enqueue { version -> Request.Changes(source.first, source.second, change.paths.toSet(),
                                change.reset, change.membershipChanged, version) }
                        }
                    }, 250L)
                }
            }
        }
        val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(directory, EVENTS) {
                override fun onEvent(event: Int, path: String?) = receive(event, path)
            }
        } else {
            object : FileObserver(path, EVENTS) {
                override fun onEvent(event: Int, path: String?) = receive(event, path)
            }
        }
        watches[path] = Watch(token, observer, canonicalPath)
        canonicalWatches[canonicalPath] = path
        observer.startWatching()
        coverageDirty = true
    }

    private fun removeWatches(predicate: (String) -> Boolean) {
        watches.keys.filter(predicate).forEach { path ->
            val watch = watches.remove(path) ?: return@forEach
            canonicalWatches.remove(watch.canonicalPath)
            watch.observer.stopWatching()
            coverageDirty = true
        }
        // Hand removed paths back to browser watchers before a replacement scan can block.
        publishCoverage()
    }

    private fun publishCoverage() {
        if (!coverageDirty) return
        coverageDirty = false
        val current = watches.keys.filterTo(linkedSetOf()) { path -> roots.any { within(path, it) } }
        if (current == publishedCoverage) return
        (publishedCoverage - current).forEach(SharedFileCache.directories::invalidate)
        publishedCoverage = current
        onCoverageChanged(current)
    }

    private fun Set<String>.withParents(): Set<String> = this + mapNotNull { File(it).parentFile?.absolutePath }

    private fun within(path: String, directory: String): Boolean =
        path == directory || path.startsWith(directory.trimEnd('/') + "/")

    private val SELF_EVENTS = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    private val MEMBERSHIP_EVENTS = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
        FileObserver.MOVED_TO or SELF_EVENTS
    private val EVENTS = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
        FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.ATTRIB or SELF_EVENTS
}
