package com.subtitleedit

import androidx.lifecycle.ViewModel
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import java.io.File

internal enum class FileOperation { COPY, MOVE, EXTRACT }

internal data class DestinationNavigationState(
    val directory: File,
    val directoryHistory: List<File>
)

internal class MainViewModel : ViewModel() {
    var currentDirectory: File? = null
    val directoryHistory = mutableListOf<File>()
    val selectedPaths = linkedSetOf<String>()
    var pendingFileOperation: FileOperation? = null
    var pendingArchiveFile: File? = null
    val destinationNavigationHistory = mutableListOf<DestinationNavigationState>()
    var searchQuery: String = ""
    var isFileSearchActive: Boolean = false
    var selectedTopLevelItem: Int = com.subtitleedit.R.id.nav_directory
    var sortField: FileSortField? = null
    var sortDirection: FileSortDirection? = null
}
