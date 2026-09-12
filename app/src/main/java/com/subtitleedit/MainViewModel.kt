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
    val documentState = MainDocumentState()
    var currentDirectory: File?
        get() = documentState.currentDirectory
        set(value) { documentState.currentDirectory = value }
    val directoryHistory get() = documentState.directoryHistory
    val directoryScrollPositions get() = documentState.directoryScrollPositions
    val selectedPaths get() = documentState.selectedPaths
    var pendingFileOperation: FileOperation?
        get() = documentState.pendingFileOperation
        set(value) { documentState.pendingFileOperation = value }
    var pendingArchiveFile: File?
        get() = documentState.pendingArchiveFile
        set(value) { documentState.pendingArchiveFile = value }
    var searchQuery: String
        get() = documentState.searchQuery
        set(value) { documentState.searchQuery = value }
    var isFileSearchActive: Boolean
        get() = documentState.isFileSearchActive
        set(value) { documentState.isFileSearchActive = value }
    var selectedTopLevelItem: Int
        get() = documentState.selectedTopLevelItem
        set(value) { documentState.selectedTopLevelItem = value }
    var sortField: FileSortField?
        get() = documentState.sortField
        set(value) { documentState.sortField = value }
    var sortDirection: FileSortDirection?
        get() = documentState.sortDirection
        set(value) { documentState.sortDirection = value }
}
