package com.subtitleedit.ui

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.subtitleedit.R
import com.subtitleedit.library.ListeningDay
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryFileObserver
import com.subtitleedit.util.SharedFileCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

/** Resolve a clicked item before removing missing files, so another item never replaces it. */
internal object LibraryPlaybackSelection {
    data class Selection(val paths: List<String>, val startIndex: Int)

    fun resolve(paths: List<String>, clickedIndex: Int, playAll: Boolean, exists: (String) -> Boolean): Selection? {
        val clickedPath = paths.getOrNull(clickedIndex)
        if (!playAll && clickedPath == null) return null
        val available = paths.distinct().filter(exists)
        if (available.isEmpty()) return null
        val index = available.indexOf(clickedPath)
        if (!playAll && index < 0) return null
        return Selection(available, index.coerceAtLeast(0))
    }
}

/** One application-owned scan survives a page change and is visible from either scan entry. */
internal object MediaLibraryScanCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)
    private val _scanning = MutableStateFlow(false)
    // Root recovery and file-observer indexing run without the manual scan entry.
    val scanning = combine(_scanning, MediaLibraryFileObserver.indexing) { queued, indexing ->
        queued || indexing
    }.stateIn(scope, SharingStarted.Eagerly, MediaLibraryFileObserver.indexing.value)

    fun scan(context: Context, directories: List<File>) {
        if (!inFlight.compareAndSet(false, true)) return
        val app = context.applicationContext
        _scanning.value = true
        scope.launch {
            try {
                val readable = directories.filter { SharedFileCache.attributes.get(it)?.isDirectory == true }
                require(readable.isNotEmpty()) { "No readable directory" }
                val count = MediaLibraryFileObserver.reconcile(app, readable)
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, app.getString(R.string.media_lib_scan_done, count), Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { showLibraryError(app, error) }
            } finally {
                _scanning.value = false
                inFlight.set(false)
            }
        }
    }
}

internal fun showLibraryError(context: Context, error: Exception) {
    Log.e("MediaLibrary", "Library operation failed", error)
    Toast.makeText(context, context.getString(R.string.player_library_operation_failed), Toast.LENGTH_SHORT).show()
}

/** Refresh on resume and exactly at the next local 05:00 boundary, including DST changes. */
@Composable
internal fun rememberListeningDate(refreshVersion: Int, revision: Long): String {
    val context = LocalContext.current
    val lifecycle = remember(context) {
        var candidate: Context? = context
        var owner: LifecycleOwner? = null
        while (candidate != null) {
            val current = candidate
            if (current is LifecycleOwner) {
                owner = current
                break
            }
            candidate = (current as? ContextWrapper)?.baseContext?.takeUnless { it === current }
        }
        owner?.lifecycle
    }
    var date by remember { mutableStateOf(ListeningDay.currentDate()) }
    LaunchedEffect(lifecycle, refreshVersion, revision) {
        suspend fun observeDate() {
            while (isActive) {
                date = ListeningDay.currentDate()
                val now = ZonedDateTime.now()
                var next = now.toLocalDate().atTime(ListeningDay.RESET_HOUR, 0).atZone(now.zone)
                if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1)
                    .atTime(ListeningDay.RESET_HOUR, 0).atZone(now.zone)
                delay(Duration.between(now, next).toMillis().coerceIn(1L, 30_000L))
            }
        }
        if (lifecycle == null) observeDate()
        else lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { observeDate() }
    }
    return date
}
