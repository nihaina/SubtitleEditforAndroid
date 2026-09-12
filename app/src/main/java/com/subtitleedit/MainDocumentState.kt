package com.subtitleedit

import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import java.io.File

/** Persistent file-browser state shared by MainActivity and its coordinators. */
internal class MainDocumentState {
    var currentDirectory: File? = null
    val directoryHistory = mutableListOf<File>()
    val directoryScrollPositions = mutableMapOf<String, DirectoryScrollPosition>()
    val selectedPaths = linkedSetOf<String>()
    var pendingFileOperation: FileOperation? = null
    var pendingArchiveFile: File? = null
    var searchQuery = ""
    var isFileSearchActive = false
    var selectedTopLevelItem = com.subtitleedit.R.id.nav_directory
    var sortField: FileSortField? = null
    var sortDirection: FileSortDirection? = null
}
