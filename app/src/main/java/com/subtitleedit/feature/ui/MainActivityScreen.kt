package com.subtitleedit.feature.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.appcompat.R as AppCompatR
import com.subtitleedit.FileOperation
import com.subtitleedit.DirectoryScrollPosition
import com.subtitleedit.MainTopLevelPages
import com.subtitleedit.MainTopLevelPagesState
import com.subtitleedit.R
import com.subtitleedit.util.AndroidDirectoryPolicy
import com.subtitleedit.util.FileOperationUiPolicy
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.FilePathPolicy
import com.subtitleedit.util.MainNavigationPolicy
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class MainDirectoryScrollRequest(
    val generation: Long,
    val directoryPath: String,
    val savedPosition: DirectoryScrollPosition?
)

internal data class MainActivityScreenState(
    val selectedTopLevelItem: Int = R.id.nav_directory,
    val toolbarTitle: String = "",
    val toolbarHasBack: Boolean = false,
    val isDirectoryLoading: Boolean = false,
    val isSearchInProgress: Boolean = false,
    val showDirectoryEmptyState: Boolean = false,
    val directoryEmptyStateMessage: Int = R.string.no_files,
    val currentDirectory: File? = null,
    val files: List<File> = emptyList(),
    val scrollRequest: MainDirectoryScrollRequest? = null,
    val relativePathRoot: File? = null,
    val selectedPaths: Set<String> = emptySet(),
    val pendingOperation: FileOperation? = null,
    val isSearchExpanded: Boolean = false,
    val searchQuery: String = "",
    val sortField: FileSortField = FileSortField.NAME,
    val sortDirection: FileSortDirection = FileSortDirection.ASCENDING,
    val canConvertSelected: Boolean = false,
    val canCompressSelected: Boolean = true
)

internal enum class MainCreateItemKind { FOLDER, FILE }
internal enum class MainCreateItemField { NAME, EXTENSION }
internal data class MainCreateItemError(val field: MainCreateItemField, val message: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainActivityScreen(
    state: MainActivityScreenState,
    topLevelPageRefreshVersion: Int,
    topLevelPageSelectionVersion: Int,
    onDirectoryListState: (LazyListState) -> Unit,
    onTopLevelPageSelected: (Int) -> Unit,
    onOpenDirectoryFromFavorites: (File) -> Unit,
    onUpdateTopLevelToolbar: (String, Boolean, (() -> Unit)?) -> Unit,
    onSearchRequested: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onCreateItem: (MainCreateItemKind, String, String) -> MainCreateItemError?,
    onSortFieldChanged: (FileSortField) -> Unit,
    onSortDirectionChanged: (FileSortDirection) -> Unit,
    onOpenFileManagementSettings: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectRange: () -> Unit,
    onToolbarBack: () -> Unit,
    onFileClick: (File) -> Unit,
    onFileLongClick: (File) -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onConvertSelection: () -> Unit,
    onCompressSelection: () -> Unit,
    onShowSelectionProperties: () -> Unit,
    onConfirmDestination: () -> Unit,
    onCancelDestination: () -> Unit,
    subtitleConversion: SubtitleConversionDialogUi?,
    subtitleConversionResult: SubtitleConversionResultUi?,
    archiveCreation: ArchiveCreationDialogUi?,
    onDismissSubtitleConversion: (Int) -> Unit,
    onConvertSubtitle: (Int, Int, Boolean) -> Boolean,
    onDismissConversionResult: () -> Unit,
    onDismissArchiveCreation: () -> Unit,
    onCreateArchive: (ArchiveCreateSubmission) -> String?,
    onLoadArchivePasswords: () -> List<String>?,
    onSaveArchivePassword: (String) -> Unit,
    onClearArchivePasswordBook: () -> Unit,
    mainDialog: MainActivityDialogUi?,
    onDismissMainDialog: () -> Unit,
    onPermissionDialogConfirm: () -> Unit,
    onRenameConfirm: (String) -> Unit,
    onDeleteConfirm: () -> Unit
) {
    val listState = rememberLazyListState()
    val topLevelPagesState = remember { MainTopLevelPagesState() }
    SideEffect { onDirectoryListState(listState) }
    var createMenuExpanded by remember { mutableStateOf(false) }
    var directoryMenuExpanded by remember { mutableStateOf(false) }
    var createDialogKind by remember { mutableStateOf<MainCreateItemKind?>(null) }
    var createName by remember { mutableStateOf("") }
    var createExtension by remember { mutableStateOf("txt") }
    var createNameError by remember { mutableStateOf<String?>(null) }
    var createExtensionError by remember { mutableStateOf<String?>(null) }
    var showSortDialog by remember { mutableStateOf(false) }
    val isDirectoryPage = state.selectedTopLevelItem == R.id.nav_directory
    // Selection actions belong to directoryContent in the legacy layout. The
    // selection state can survive a top-level tab switch, but its toolbar and
    // bottom action row stay hidden until the directory tab is visible again.
    val isSelectionActive = isDirectoryPage &&
        (state.selectedPaths.isNotEmpty() || state.pendingOperation != null)
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val createNameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(createDialogKind) {
        if (createDialogKind != null) {
            createName = ""
            createExtension = "txt"
            createNameError = null
            createExtensionError = null
        }
        if (createDialogKind == MainCreateItemKind.FILE) {
            createNameFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    LaunchedEffect(state.isSearchExpanded, isSelectionActive, isDirectoryPage) {
        if (state.isSearchExpanded && !isSelectionActive && isDirectoryPage) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            modifier = Modifier.height(56.dp),
            title = {
                if (state.isSearchExpanded && isDirectoryPage && !isSelectionActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = state.searchQuery,
                            onValueChange = onSearchChanged,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .focusRequester(searchFocusRequester),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            keyboardOptions = KeyboardOptions.Default.copy(
                                autoCorrectEnabled = false,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search
                            ),
                            // SearchView's submit listener returned true without
                            // changing the query or collapsing the action view.
                            keyboardActions = KeyboardActions(onSearch = {}),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (state.searchQuery.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.file_search_hint),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        IconButton(
                            onClick = {
                                if (state.searchQuery.isEmpty()) onToolbarBack()
                                else onSearchChanged("")
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                painterResource(AppCompatR.drawable.abc_ic_clear_material),
                                contentDescription = if (state.searchQuery.isEmpty()) {
                                    stringResource(R.string.cancel)
                                } else {
                                    "清除搜索"
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                } else {
                    val title = when {
                        state.toolbarTitle.isNotBlank() -> state.toolbarTitle
                        isSelectionActive -> FileOperationUiPolicy.selectionTitle(
                            state.pendingOperation,
                            state.selectedPaths.size
                        )
                        isDirectoryPage -> stringResource(R.string.nav_directory)
                        else -> stringResource(MainNavigationPolicy.titleRes(state.selectedTopLevelItem))
                    }
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            navigationIcon = {
                if (state.toolbarHasBack || isSelectionActive) {
                    IconButton(onClick = onToolbarBack) {
                        Icon(
                            painter = painterResource(
                                if (isSelectionActive || state.isSearchExpanded) R.drawable.ic_close
                                else R.drawable.ic_back
                            ),
                            contentDescription = stringResource(
                                R.string.cancel
                            )
                        )
                    }
                }
            },
            actions = {
                if (isDirectoryPage && isSelectionActive) {
                    // The legacy menu keeps these actions available while a
                    // selection is active, except during destination picking.
                    if (state.pendingOperation == null && state.selectedPaths.isNotEmpty()) {
                        IconButton(onClick = onSelectAll) {
                            Icon(
                                painterResource(R.drawable.ic_select_all),
                                contentDescription = stringResource(R.string.source_selection_select_all)
                            )
                        }
                        IconButton(onClick = onSelectRange) {
                            Icon(
                                painterResource(R.drawable.ic_select_range),
                                contentDescription = "局部全选"
                            )
                        }
                    }
                } else if (isDirectoryPage) {
                    if (!state.isSearchExpanded) {
                        IconButton(onClick = onSearchRequested) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.menu_search))
                        }
                    }
                    Box {
                        IconButton(onClick = { createMenuExpanded = true }) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.menu_new))
                        }
                        DropdownMenu(
                            expanded = createMenuExpanded,
                            onDismissRequest = { createMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建文件夹") },
                                onClick = {
                                    createMenuExpanded = false
                                    createDialogKind = MainCreateItemKind.FOLDER
                                    createName = ""
                                    createExtension = "txt"
                                    createNameError = null
                                    createExtensionError = null
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("新建文件") },
                                onClick = {
                                    createMenuExpanded = false
                                    createDialogKind = MainCreateItemKind.FILE
                                    createName = ""
                                    createExtension = "txt"
                                    createNameError = null
                                    createExtensionError = null
                                }
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { directoryMenuExpanded = true }) {
                            Icon(painterResource(R.drawable.ic_more_vertical), contentDescription = stringResource(R.string.activity_main_text_01))
                        }
                        DropdownMenu(
                            expanded = directoryMenuExpanded,
                            onDismissRequest = { directoryMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("排序") },
                                onClick = {
                                    directoryMenuExpanded = false
                                    showSortDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = {
                                    directoryMenuExpanded = false
                                    onOpenFileManagementSettings()
                                }
                            )
                        }
                    }
                }
            }
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isDirectoryPage) {
                DirectoryBrowserContent(
                    state = state,
                    listState = listState,
                    onFileClick = onFileClick,
                    onFileLongClick = onFileLongClick
                )
            } else {
                MainTopLevelPages(
                    selectedPage = state.selectedTopLevelItem,
                    refreshVersion = topLevelPageRefreshVersion,
                    selectionVersion = topLevelPageSelectionVersion,
                    state = topLevelPagesState,
                    onOpenDirectory = onOpenDirectoryFromFavorites,
                    onToolbarChanged = onUpdateTopLevelToolbar
                )
            }
        }

        if (isSelectionActive) {
            SelectionActions(
                operation = state.pendingOperation,
                onCopy = onCopy,
                onMove = onMove,
                onRename = onRename,
                onDelete = onDelete,
                canConvert = state.canConvertSelected,
                canCompress = state.canCompressSelected,
                onConvert = onConvertSelection,
                onCompress = onCompressSelection,
                onProperties = onShowSelectionProperties,
                onConfirmDestination = onConfirmDestination,
                onCancelDestination = onCancelDestination
            )
        }
        // The legacy activity hides bottom navigation while a file selection or
        // destination operation is active. It returns after the operation exits.
        if (state.selectedPaths.isEmpty() && state.pendingOperation == null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MainBottomNavigation(
                selectedItemId = state.selectedTopLevelItem,
                onSelected = onTopLevelPageSelected
            )
        }
    }

    createDialogKind?.let { kind ->
        AlertDialog(
            onDismissRequest = { createDialogKind = null },
            title = { Text(if (kind == MainCreateItemKind.FOLDER) "新建文件夹" else "新建文件") },
            text = {
                Column {
                    OutlinedTextField(
                        value = createName,
                        onValueChange = { createName = it; createNameError = null },
                        modifier = Modifier.fillMaxWidth().focusRequester(createNameFocusRequester),
                        placeholder = { Text(if (kind == MainCreateItemKind.FOLDER) "文件夹名称" else "文件名") },
                        singleLine = true,
                        isError = createNameError != null,
                        supportingText = { createNameError?.let { Text(it) } }
                    )
                    if (kind == MainCreateItemKind.FILE) {
                        OutlinedTextField(
                            value = createExtension,
                            onValueChange = { createExtension = it; createExtensionError = null },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("扩展名") },
                            singleLine = true,
                            isError = createExtensionError != null,
                            supportingText = { createExtensionError?.let { Text(it) } }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val error = onCreateItem(kind, createName, createExtension)
                    if (error == null) {
                        createDialogKind = null
                    } else if (error.field == MainCreateItemField.NAME) {
                        createNameError = error.message
                    } else {
                        createExtensionError = error.message
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { createDialogKind = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("排序") },
            text = {
                Column(Modifier.padding(8.dp)) {
                    listOf(
                        "名称" to FileSortField.NAME,
                        "类型" to FileSortField.TYPE,
                        "大小" to FileSortField.SIZE,
                        "日期" to FileSortField.DATE
                    ).forEach { (label, field) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(
                                    if (state.sortField == field) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { onSortFieldChanged(field) }
                                .padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
                            RadioButton(
                                selected = state.sortField == field,
                                onClick = null,
                                modifier = Modifier.width(40.dp)
                            )
                        }
                    }
                    listOf(
                        "升序" to FileSortDirection.ASCENDING,
                        "降序" to FileSortDirection.DESCENDING
                    ).forEach { (label, direction) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(
                                    if (state.sortDirection == direction) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { onSortDirectionChanged(direction) }
                                .padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
                            RadioButton(
                                selected = state.sortDirection == direction,
                                onClick = null,
                                modifier = Modifier.width(40.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSortDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    MainFileOperationDialogs(
        subtitleConversion = subtitleConversion,
        subtitleConversionResult = subtitleConversionResult,
        archiveCreation = archiveCreation,
        onDismissSubtitleConversion = onDismissSubtitleConversion,
        onConvertSubtitle = onConvertSubtitle,
        onDismissConversionResult = onDismissConversionResult,
        onDismissArchiveCreation = onDismissArchiveCreation,
        onCreateArchive = onCreateArchive,
        onLoadArchivePasswords = onLoadArchivePasswords,
        onSaveArchivePassword = onSaveArchivePassword,
        onClearArchivePasswordBook = onClearArchivePasswordBook,
        mainDialog = mainDialog,
        onDismissMainDialog = onDismissMainDialog,
        onPermissionDialogConfirm = onPermissionDialogConfirm,
        onRenameConfirm = onRenameConfirm,
        onDeleteConfirm = onDeleteConfirm
    )
}

@Composable
private fun DirectoryBrowserContent(
    state: MainActivityScreenState,
    listState: LazyListState,
    onFileClick: (File) -> Unit,
    onFileLongClick: (File) -> Unit
) {
    val scrollRequest = state.scrollRequest
    var appliedScrollGeneration by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(scrollRequest, state.files, state.currentDirectory) {
        val request = scrollRequest ?: return@LaunchedEffect
        if (appliedScrollGeneration == request.generation || state.files.isEmpty()) return@LaunchedEffect
        if (state.currentDirectory?.let(FilePathPolicy::canonicalOrAbsolute) != request.directoryPath) {
            return@LaunchedEffect
        }
        val savedPosition = request.savedPosition
        val position = savedPosition?.firstVisiblePath?.let { path ->
            state.files.indexOfFirst { it.absolutePath == path }
        }?.takeIf { it >= 0 } ?: savedPosition?.firstVisibleIndex ?: 0
        listState.scrollToItem(position.coerceIn(0, state.files.lastIndex), savedPosition?.offset ?: 0)
        appliedScrollGeneration = request.generation
    }
    Column(Modifier.fillMaxSize()) {
        state.currentDirectory?.let { directory ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = directory.absolutePath,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        // The legacy directory screen only exposed progress while a recursive
        // search was running. Loading a directory kept the existing list and
        // did not replace the empty-state text with the search message.
        if (state.isSearchInProgress) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
        } else {
            Spacer(Modifier.fillMaxWidth().height(2.dp))
        }

        if (state.showDirectoryEmptyState) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(state.directoryEmptyStateMessage),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else if (state.files.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
            ) {
                items(state.files, key = { it.absolutePath }) { file ->
                    BrowserFileRow(
                        file = file,
                        relativePathRoot = state.relativePathRoot,
                        isSelected = file.absolutePath in state.selectedPaths,
                        isSelectionMode = state.selectedPaths.isNotEmpty() && state.pendingOperation == null,
                        onClick = { onFileClick(file) },
                        onLongClick = { onFileLongClick(file) }
                    )
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowserFileRow(
    file: File,
    relativePathRoot: File?,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    restrictedOverride: Boolean? = null
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    // FavoritesFragment historically used FileListAdapter without an
    // isItemRestricted callback. Preserve that behavior for its rows while
    // keeping the Android/data restriction for the main browser.
    val restricted = remember(file.absolutePath, restrictedOverride) {
        restrictedOverride ?: AndroidDirectoryPolicy.isRestricted(file)
    }
    val previewKey = remember(file.absolutePath, file.length(), file.lastModified()) {
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"
    }
    val targetSize = remember(context) { (48 * context.resources.displayMetrics.density + 0.5f).toInt() }
    var preview by remember(previewKey) { mutableStateOf<MainFilePreview?>(null) }
    var directoryCount by remember(previewKey) { mutableStateOf<Int?>(null) }
    LaunchedEffect(previewKey) {
        preview = withContext(Dispatchers.IO) {
            MainFilePreviewLoader.preview(context, file, targetSize)
        }
        if (file.isDirectory && file.name != ".." && !restricted) {
            directoryCount = withContext(Dispatchers.IO) {
                MainFilePreviewLoader.directoryItemCount(file)
            }
        }
    }
    val displayName = remember(file.absolutePath, relativePathRoot?.absolutePath) {
        relativePathRoot?.let { root -> runCatching { file.relativeTo(root).path }.getOrDefault(file.name) }
            ?: file.name
    }
    val modifiedTime = remember(file.lastModified(), locale) {
        SimpleDateFormat("yyyy-MM-dd", locale).format(Date(file.lastModified()))
    }
    val alpha = when {
        isSelectionMode && !isSelected -> 0.72f
        restricted -> 0.55f
        else -> 1f
    }
    val selectionStrokeWidth = with(LocalDensity.current) { 2.toDp() }
    val border = if (isSelected) {
        BorderStroke(selectionStrokeWidth, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(0.dp, Color.Transparent)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .alpha(alpha)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (restricted) onClick() else onLongClick() }
            ),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = border,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileRowIcon(file, preview?.bitmap, restricted)
            Column(
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = displayName,
                    color = if (restricted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                if (file.isDirectory && file.name != "..") {
                    if (!restricted) {
                        val count = directoryCount
                        if (count == null || count >= 0) {
                            Text(
                                text = when {
                                    count == null -> ""
                                    count == 0 -> stringResource(R.string.directory_empty)
                                    else -> stringResource(R.string.directory_item_count, count)
                                },
                                modifier = Modifier.padding(top = 4.dp)
                                    .alpha(if (count == null) 0f else 1f),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                } else if (!file.isDirectory) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = FileUtils.formatFileSize(file.length()),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                        val mediaFile = MainFilePreviewLoader.isMediaFile(file)
                        if (mediaFile && preview == null) {
                            Text(
                                text = "00:00",
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .width(72.dp)
                                    .alpha(0f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        } else {
                            preview?.mediaDuration?.takeIf(String::isNotEmpty)?.let { duration ->
                                Text(
                                    text = duration,
                                    modifier = Modifier.padding(start = 12.dp).width(72.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.padding(start = 8.dp),
                horizontalAlignment = Alignment.End
            ) {
                if (file.isDirectory) {
                    if (file.name != "..") {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = modifiedTime,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 5.dp),
                            maxLines = 1
                        )
                    }
                } else {
                    if (file.extension.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = file.extension.uppercase(locale),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color.White,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                    Text(
                        text = modifiedTime,
                        modifier = Modifier.padding(top = 5.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRowIcon(file: File, bitmap: android.graphics.Bitmap?, restricted: Boolean) {
    val iconDescription = stringResource(R.string.file_name)
    Box(
        modifier = Modifier.size(48.dp).alpha(
            if (file.name.startsWith(".") && file.name != "..") 0.5f else 1f
        ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            val isApk = file.extension.equals("apk", ignoreCase = true)
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = iconDescription,
                modifier = if (isApk) Modifier.fillMaxSize().padding(8.dp) else Modifier.fillMaxSize(),
                contentScale = if (isApk) ContentScale.Fit else ContentScale.Crop
            )
        } else {
            Icon(
                painter = painterResource(MainFilePreviewLoader.iconResource(file)),
                contentDescription = iconDescription,
                modifier = Modifier.size(32.dp),
                tint = if (restricted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
            )
        }
    }
}

@Composable
private fun SelectionActions(
    operation: FileOperation?,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    canConvert: Boolean,
    canCompress: Boolean,
    onConvert: () -> Unit,
    onCompress: () -> Unit,
    onProperties: () -> Unit,
    onConfirmDestination: () -> Unit,
    onCancelDestination: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Spacer(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surface))
    if (operation == null) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionAction(Modifier.weight(1f), R.drawable.ic_file_copy, stringResource(R.string.activity_main_contentdescription_01), onCopy)
            SelectionAction(Modifier.weight(1f), R.drawable.ic_file_move, stringResource(R.string.activity_main_contentdescription_02), onMove)
            SelectionAction(Modifier.weight(1f), R.drawable.ic_rename, stringResource(R.string.activity_main_contentdescription_03), onRename)
            SelectionAction(Modifier.weight(1f), R.drawable.ic_delete_normal, stringResource(R.string.delete), onDelete)
            Box(Modifier.weight(1f)) {
                SelectionAction(
                    Modifier.fillMaxWidth(),
                    R.drawable.ic_more,
                    stringResource(R.string.activity_main_text_01)
                ) { menuExpanded = true }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (canConvert) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.convert_format)) },
                            onClick = { menuExpanded = false; onConvert() }
                        )
                    }
                    if (canCompress) {
                        DropdownMenuItem(
                            text = { Text("压缩") },
                            onClick = { menuExpanded = false; onCompress() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("详情") },
                        onClick = { menuExpanded = false; onProperties() }
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.activity_main_text_02),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            TextButton(onClick = onCancelDestination) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onConfirmDestination) {
                Text(FileOperationUiPolicy.destinationButtonLabel(operation))
            }
        }
    }
}

@Composable
private fun SelectionAction(modifier: Modifier, icon: Int, label: String, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .height(64.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = Color.Unspecified
        )
        Text(label, modifier = Modifier.padding(top = 2.dp), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun MainBottomNavigation(selectedItemId: Int, onSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val items = listOf(
            Triple(R.id.nav_directory, R.drawable.ic_nav_folder, R.string.nav_directory),
            Triple(R.id.nav_favorites, R.drawable.ic_favorite, R.string.nav_favorites),
            Triple(R.id.nav_drafts, R.drawable.ic_nav_draft, R.string.drafts),
            Triple(R.id.nav_tools, R.drawable.ic_tools, R.string.menu_main_title_01),
            Triple(R.id.nav_settings, R.drawable.ic_settings, R.string.menu_main_title_02)
        )
        items.forEach { (id, icon, title) ->
            val label = stringResource(title)
            val tint = if (selectedItemId == id) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    // BottomNavigationView dispatched the selection callback even
                    // when the tapped item was already selected. MainActivity uses
                    // that callback to recreate/reset the corresponding top-level
                    // page, so repeated taps must remain observable here.
                    .clickable { onSelected(id) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = tint
                )
                Text(label, color = tint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}
