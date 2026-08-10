package com.subtitleedit

import androidx.lifecycle.ViewModel
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import java.io.File

internal enum class FileOperation { COPY, MOVE, EXTRACT }

internal data class DirectoryScrollPosition(
    val firstVisiblePath: String?,
    val firstVisibleIndex: Int,
    val offset: Int
)

internal class MainViewModel : ViewModel() {
    var currentDirectory: File? = null
    val directoryHistory = mutableListOf<File>()
    val directoryScrollPositions = mutableMapOf<String, DirectoryScrollPosition>()
    val selectedPaths = linkedSetOf<String>()
    var pendingFileOperation: FileOperation? = null
    var pendingArchiveFile: File? = null
    var searchQuery: String = ""
    var isFileSearchActive: Boolean = false
    var selectedTopLevelItem: Int = com.subtitleedit.R.id.nav_directory
    var sortField: FileSortField? = null
    var sortDirection: FileSortDirection? = null
}
