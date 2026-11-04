package com.subtitleedit.filepicker

import android.app.Application
import android.os.Build
import android.os.storage.StorageManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.subtitleedit.AppViewModel
import com.subtitleedit.R
import com.subtitleedit.model.FileBrowserOrder
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.util.AndroidDirectoryPolicy
import com.subtitleedit.util.SelectionRangePolicy
import com.subtitleedit.util.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class FilePickerLocation(val label: String, val directory: File)

internal data class FilePickerUiState(
    val mode: FilePickerMode = FilePickerMode.OPEN_FILE,
    val directory: File = FilePickerPolicy.defaultRoot(),
    val files: List<File> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val query: String = "",
    val showHidden: Boolean = false,
    val filterUnsupported: Boolean = false,
    val unsupportedPaths: Set<String> = emptySet(),
    val sortField: FileSortField = FileSortField.NAME,
    val sortDirection: FileSortDirection = FileSortDirection.ASCENDING,
    val suggestedName: String = "",
    val hasStoragePermission: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val pendingOverwrite: File? = null,
    val locations: List<FilePickerLocation> = emptyList()
)

internal sealed interface FilePickerEvent {
    data class Selected(val files: List<File>) : FilePickerEvent
    data object Cancelled : FilePickerEvent
}

/** Shared local browser state; no selected file is copied or opened by this screen. */
internal class FilePickerViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AppViewModel<FilePickerUiState, FilePickerEvent>(application, FilePickerUiState()) {
    private var request: FilePickerRequest? = null
    private var loadJob: Job? = null
    private var actionJob: Job? = null
    private var loadGeneration = 0L
    private val settings = SettingsManager.getInstance(application)

    fun initialize(value: FilePickerRequest) {
        if (request != null) return
        request = value
        val initial = FilePickerPolicy.initialDirectory(app, savedState["directory"] ?: value.initialPath)
        val restoredSelection = savedState.get<ArrayList<String>>("selected").orEmpty()
            .filter { isSelectableFile(File(it)) }.toSet()
        savedState["selected"] = ArrayList(restoredSelection)
        setState {
            copy(
                mode = value.mode,
                directory = initial,
                selectedPaths = restoredSelection,
                query = savedState["query"] ?: "",
                showHidden = savedState["hidden"] ?: settings.isShowHiddenFilesEnabled(),
                filterUnsupported = savedState["filterUnsupported"] ?: false,
                sortField = savedState.get<String>("sortField")?.let { runCatching { FileSortField.valueOf(it) }.getOrNull() }
                    ?: settings.getFileSortField(),
                sortDirection = savedState.get<String>("sortDirection")?.let { runCatching { FileSortDirection.valueOf(it) }.getOrNull() }
                    ?: settings.getFileSortDirection(),
                suggestedName = savedState["name"] ?: value.suggestedName.orEmpty()
            )
        }
        viewModelScope.launch {
            val locations = withContext(Dispatchers.IO) { loadLocations() }
            setState { copy(locations = locations) }
        }
    }

    fun updateStoragePermission(granted: Boolean) {
        val changed = currentState.hasStoragePermission != granted
        setState { copy(hasStoragePermission = granted) }
        if (granted && (changed || currentState.files.isEmpty())) refresh()
    }

    fun navigate(directory: File): Boolean {
        if (AndroidDirectoryPolicy.isRestricted(directory)) {
            toast(R.string.file_picker_directory_inaccessible)
            return false
        }
        if (!directory.isAbsolute || !directory.isDirectory) {
            toast(R.string.directory_not_found)
            return false
        }
        if (!directory.canRead()) {
            toast(R.string.file_picker_directory_inaccessible)
            return false
        }
        val absolute = directory.absoluteFile
        savedState["directory"] = absolute.path
        savedState["query"] = ""
        setState { copy(directory = absolute, query = "", files = emptyList(), error = null) }
        refresh()
        return true
    }

    fun back() {
        if (currentState.selectedPaths.isNotEmpty()) {
            clearSelection()
            return
        }
        if (currentState.query.isNotEmpty()) {
            search("")
            return
        }
        val directory = currentState.directory
        val isRoot = directory.absolutePath == FilePickerPolicy.defaultRoot().absolutePath ||
            currentState.locations.any { it.directory.absolutePath == directory.absolutePath }
        val parent = directory.parentFile
        if (isRoot || parent == null || !parent.canRead()) sendEvent(FilePickerEvent.Cancelled)
        else navigate(parent)
    }

    fun cancel() = sendEvent(FilePickerEvent.Cancelled)

    fun search(query: String) {
        savedState["query"] = query
        setState { copy(query = query) }
        refresh()
    }

    fun setShowHidden(show: Boolean) {
        savedState["hidden"] = show
        setState { copy(showHidden = show) }
        refresh()
    }

    fun setFilterUnsupported(enabled: Boolean) {
        savedState["filterUnsupported"] = enabled
        setState { copy(filterUnsupported = enabled) }
        refresh()
    }

    fun sort(field: FileSortField, direction: FileSortDirection) {
        savedState["sortField"] = field.name
        savedState["sortDirection"] = direction.name
        setState { copy(sortField = field, sortDirection = direction) }
        refresh()
    }

    fun setName(name: String) {
        savedState["name"] = name
        setState { copy(suggestedName = name, error = null) }
    }

    fun select(file: File) {
        if (file.isDirectory) {
            navigate(file)
            return
        }
        if (!file.isFile || !file.canRead()) {
            toast(R.string.file_picker_file_inaccessible)
            return
        }
        if (!isSupported(file)) {
            toast(R.string.file_picker_unsupported_file)
            return
        }
        when (currentState.mode) {
            FilePickerMode.OPEN_FILE -> sendEvent(FilePickerEvent.Selected(listOf(file)))
            FilePickerMode.OPEN_FILES -> {
                val selected = currentState.selectedPaths.toMutableSet()
                if (!selected.add(file.absolutePath)) selected.remove(file.absolutePath)
                savedState["selected"] = ArrayList(selected)
                setState { copy(selectedPaths = selected) }
            }
            FilePickerMode.SAVE_FILE -> setName(file.name)
            FilePickerMode.DIRECTORY -> Unit
        }
    }

    fun clearSelection() {
        savedState["selected"] = arrayListOf<String>()
        setState { copy(selectedPaths = emptySet()) }
    }

    fun selectAll() {
        val snapshot = currentState
        if (snapshot.mode != FilePickerMode.OPEN_FILES) return
        val selectable = snapshot.files.filter(::isSelectableFile)
        if (selectable.isEmpty()) return
        if (selectable.all { it.absolutePath in snapshot.selectedPaths }) {
            clearSelection()
            return
        }
        updateSelection(snapshot.selectedPaths + selectable.map { it.absolutePath })
    }

    fun selectRange() {
        val snapshot = currentState
        if (snapshot.mode != FilePickerMode.OPEN_FILES) return
        val selectedIndices = snapshot.files.mapIndexedNotNull { index, file ->
            index.takeIf { file.absolutePath in snapshot.selectedPaths && isSelectableFile(file) }
        }
        val range = SelectionRangePolicy.contiguousRange(selectedIndices)
        if (range == null) {
            toast("请先在当前目录选择两个文件")
            return
        }
        val paths = snapshot.files.subList(range.first, range.last + 1)
            .filter(::isSelectableFile)
            .map { it.absolutePath }
        updateSelection(snapshot.selectedPaths + paths)
    }

    private fun updateSelection(paths: Set<String>) {
        savedState["selected"] = ArrayList(paths)
        setState { copy(selectedPaths = paths) }
    }

    private fun isSupported(file: File): Boolean = request?.let { FilePickerPolicy.isSupported(file, it) } ?: true

    private fun isSelectableFile(file: File): Boolean = file.isFile && file.canRead() && isSupported(file)

    fun confirm() {
        if (actionJob?.isActive == true) return
        val snapshot = currentState
        actionJob = viewModelScope.launch {
            val selected = when (snapshot.mode) {
                FilePickerMode.DIRECTORY -> listOf(snapshot.directory)
                FilePickerMode.OPEN_FILES -> snapshot.selectedPaths.map(::File)
                FilePickerMode.SAVE_FILE -> {
                    if (!FilePickerPolicy.validateFileName(snapshot.suggestedName)) {
                        setState { copy(error = string(R.string.file_picker_invalid_name)) }
                        return@launch
                    }
                    listOf(File(snapshot.directory, snapshot.suggestedName))
                }
                FilePickerMode.OPEN_FILE -> return@launch
            }
            if (selected.isEmpty()) return@launch
            // A restored result or an external rename may have invalidated an earlier selection.
            if (snapshot.mode == FilePickerMode.OPEN_FILES && selected.any { !isSupported(it) }) {
                updateSelection(selected.filter(::isSupported).map { it.absolutePath }.toSet())
                toast(R.string.file_picker_unsupported_file)
                return@launch
            }
            val accessible = withContext(Dispatchers.IO) {
                selected.all { file ->
                    when (snapshot.mode) {
                        FilePickerMode.DIRECTORY -> file.isDirectory && file.canRead()
                        FilePickerMode.SAVE_FILE -> file.parentFile?.canWrite() == true &&
                            !file.isDirectory && (!file.exists() || file.canWrite())
                        else -> file.isFile && file.canRead()
                    }
                }
            }
            if (!accessible) {
                toast(if (snapshot.mode == FilePickerMode.DIRECTORY || snapshot.mode == FilePickerMode.SAVE_FILE)
                    R.string.file_picker_directory_not_writable else R.string.file_picker_file_inaccessible)
                return@launch
            }
            val target = selected.singleOrNull()
            if (snapshot.mode == FilePickerMode.SAVE_FILE && target?.exists() == true) {
                setState { copy(pendingOverwrite = target) }
            } else sendEvent(FilePickerEvent.Selected(selected))
        }
    }

    fun dismissOverwrite() = setState { copy(pendingOverwrite = null) }

    fun overwrite() {
        val target = currentState.pendingOverwrite ?: return
        dismissOverwrite()
        sendEvent(FilePickerEvent.Selected(listOf(target)))
    }

    fun useUniqueName() {
        val target = currentState.pendingOverwrite ?: return
        dismissOverwrite()
        viewModelScope.launch {
            val unique = withContext(Dispatchers.IO) {
                val extension = target.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
                val stem = if (extension.isEmpty()) target.name else target.name.removeSuffix(extension)
                var index = 1
                var candidate: File
                do { candidate = File(target.parentFile, "$stem (${index++})$extension") } while (candidate.exists())
                candidate
            }
            setName(unique.name)
            sendEvent(FilePickerEvent.Selected(listOf(unique)))
        }
    }

    fun createDirectory(name: String, onResult: (String?) -> Unit) {
        if (!FilePickerPolicy.validateFileName(name)) {
            onResult(string(R.string.file_picker_invalid_name))
            return
        }
        val parent = currentState.directory
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) { File(parent, name).let { !it.exists() && it.mkdir() } }
            if (created) {
                onResult(null)
                refresh()
            } else onResult(string(R.string.file_picker_create_directory_failed))
        }
    }

    fun refresh() {
        if (!currentState.hasStoragePermission || request == null) return
        loadJob?.cancel()
        val generation = ++loadGeneration
        val snapshot = currentState
        val pickerRequest = request!!
        setState { copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val listing = withContext(Dispatchers.IO) {
                    val children = snapshot.directory.listFiles()
                        ?: throw IllegalStateException(string(R.string.file_picker_directory_inaccessible))
                    val unsupported = mutableSetOf<String>()
                    val filtered = children.filter { file ->
                        ensureActive()
                        if (!snapshot.showHidden && file.name.startsWith('.')) return@filter false
                        if (file.isDirectory) return@filter true
                        if (snapshot.mode == FilePickerMode.DIRECTORY) return@filter false
                        val supported = FilePickerPolicy.isSupported(file, pickerRequest)
                        if (!supported) unsupported += file.absolutePath
                        !snapshot.filterUnsupported || supported
                    }
                    FileBrowserOrder.sort(FileBrowserOrder.filter(filtered, snapshot.query), snapshot.sortField, snapshot.sortDirection) to unsupported
                }
                if (generation == loadGeneration) setState {
                    copy(files = listing.first, unsupportedPaths = listing.second, loading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == loadGeneration) setState {
                    copy(files = emptyList(), unsupportedPaths = emptySet(), loading = false, error = error.message)
                }
            }
        }
    }

    private fun loadLocations(): List<FilePickerLocation> {
        val root = FilePickerPolicy.defaultRoot()
        val locations = mutableListOf(FilePickerLocation(string(R.string.file_picker_internal_storage), root))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            app.getSystemService(StorageManager::class.java).storageVolumes.forEach { volume ->
                volume.directory?.takeIf { it.isDirectory }?.let { directory ->
                    locations += FilePickerLocation(volume.getDescription(app), directory)
                }
            }
        } else {
            File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }
                ?.forEach { locations += FilePickerLocation(it.name, it) }
        }
        val favorites = app.getSharedPreferences("favorite_directories", Application.MODE_PRIVATE)
            .getStringSet("paths", emptySet()).orEmpty().toList()
        favorites.map(::File).filter { it.isDirectory && it.canRead() }.forEach {
            locations += FilePickerLocation(string(R.string.file_picker_favorite, it.name), it)
        }
        return locations.distinctBy { it.directory.absolutePath }
    }
}
