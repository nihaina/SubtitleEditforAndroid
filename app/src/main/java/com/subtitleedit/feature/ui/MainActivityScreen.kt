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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.FileOperation
import com.subtitleedit.MainTopLevelPages
import com.subtitleedit.MainTopLevelPagesState
import com.subtitleedit.R
import com.subtitleedit.util.AndroidDirectoryPolicy
import com.subtitleedit.util.FileOperationUiPolicy
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.MainNavigationPolicy
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class MainActivityScreenState(
    val selectedTopLevelItem: Int = R.id.nav_directory,
    val toolbarTitle: String = "",
    val toolbarHasBack: Boolean = false,
    val isDirectoryLoading: Boolean = false,
    val isSearchInProgress: Boolean = false,
    val currentDirectory: File? = null,
    val files: List<File> = emptyList(),
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
    val isSelectionActive = state.selectedPaths.isNotEmpty() || state.pendingOperation != null
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
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
            title = {
                if (state.isSearchExpanded && isDirectoryPage && !isSelectionActive) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchChanged,
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                        placeholder = { Text(stringResource(R.string.file_search_hint)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
                    )
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
                if (state.toolbarHasBack || isSelectionActive || state.isSearchExpanded) {
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
                if (isDirectoryPage && !isSelectionActive) {
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
                } else if (isDirectoryPage && state.pendingOperation == null && state.selectedPaths.isNotEmpty()) {
                    IconButton(onClick = onSelectAll) {
                        Icon(painterResource(R.drawable.ic_select_all), contentDescription = stringResource(R.string.source_selection_select_all))
                    }
                    IconButton(onClick = onSelectRange) {
                        Icon(painterResource(R.drawable.ic_select_range), contentDescription = "局部全选")
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
        } else {
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = createName,
                        onValueChange = { createName = it; createNameError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (kind == MainCreateItemKind.FOLDER) "文件夹名称" else "文件名") },
                        singleLine = true,
                        isError = createNameError != null,
                        supportingText = { createNameError?.let { Text(it) } }
                    )
                    if (kind == MainCreateItemKind.FILE) {
                        OutlinedTextField(
                            value = createExtension,
                            onValueChange = { createExtension = it; createExtensionError = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("扩展名") },
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
                Column {
                    Text("排序方式", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        "名称" to FileSortField.NAME,
                        "类型" to FileSortField.TYPE,
                        "大小" to FileSortField.SIZE,
                        "日期" to FileSortField.DATE
                    ).forEach { (label, field) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSortFieldChanged(field) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.sortField == field,
                                onClick = { onSortFieldChanged(field) }
                            )
                            Text(label)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text("顺序", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        "升序" to FileSortDirection.ASCENDING,
                        "降序" to FileSortDirection.DESCENDING
                    ).forEach { (label, direction) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSortDirectionChanged(direction) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.sortDirection == direction,
                                onClick = { onSortDirectionChanged(direction) }
                            )
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSortDialog = false }) { Text(stringResource(R.string.confirm)) }
            },
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
    Column(Modifier.fillMaxSize()) {
        state.currentDirectory?.let { directory ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = directory.absolutePath,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        if (state.isDirectoryLoading || state.isSearchInProgress) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
        } else {
            Spacer(Modifier.fillMaxWidth().height(2.dp))
        }

        if (state.files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(
                        if (state.isDirectoryLoading || state.isSearchInProgress) R.string.file_searching
                        else R.string.no_files
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 4.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.files, key = { it.absolutePath }) { file ->
                    MainFileRow(
                        file = file,
                        relativePathRoot = state.relativePathRoot,
                        isSelected = file.absolutePath in state.selectedPaths,
                        isSelectionMode = state.selectedPaths.isNotEmpty() && state.pendingOperation == null,
                        onClick = { onFileClick(file) },
                        onLongClick = { onFileLongClick(file) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainFileRow(
    file: File,
    relativePathRoot: File?,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val restricted = remember(file.absolutePath) { AndroidDirectoryPolicy.isRestricted(file) }
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
        file.name.startsWith(".") && file.name != ".." -> 0.72f
        else -> 1f
    }
    val border = if (isSelected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(0.dp, Color.Transparent)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (restricted) onClick() else onLongClick() }
            ),
        shape = MaterialTheme.shapes.small,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = border,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileRowIcon(file, preview?.bitmap, restricted)
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = displayName,
                    color = if (restricted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (file.isDirectory && file.name != "..") {
                    if (!restricted && directoryCount != null) {
                        val count = directoryCount!!
                        Text(
                            text = if (count < 0) "" else if (count == 0) stringResource(R.string.directory_empty)
                            else stringResource(R.string.directory_item_count, count),
                            modifier = Modifier.padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                } else if (!file.isDirectory) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = FileUtils.formatFileSize(file.length()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                        preview?.mediaDuration?.takeIf(String::isNotEmpty)?.let { duration ->
                            Text(
                                text = duration,
                                modifier = Modifier.padding(start = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (file.isDirectory) {
                    if (file.name != "..") {
                        Text(
                            text = modifiedTime,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                } else {
                    if (file.extension.isNotEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = file.extension.uppercase(locale),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                    Text(
                        text = modifiedTime,
                        modifier = Modifier.padding(top = 5.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRowIcon(file: File, bitmap: android.graphics.Bitmap?, restricted: Boolean) {
    val iconColor = if (restricted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.size(48.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.small),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(MainFilePreviewLoader.iconResource(file)),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = iconColor
                )
            }
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
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (operation == null) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).background(MaterialTheme.colorScheme.surface),
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.activity_main_text_02),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
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
            .height(68.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
        Text(label, modifier = Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun MainBottomNavigation(selectedItemId: Int, onSelected: (Int) -> Unit) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth().height(60.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
    ) {
        val items = listOf(
            Triple(R.id.nav_directory, R.drawable.ic_nav_folder, R.string.nav_directory),
            Triple(R.id.nav_favorites, R.drawable.ic_favorite, R.string.nav_favorites),
            Triple(R.id.nav_drafts, R.drawable.ic_nav_draft, R.string.drafts),
            Triple(R.id.nav_tools, R.drawable.ic_tools, R.string.menu_main_title_01),
            Triple(R.id.nav_settings, R.drawable.ic_settings, R.string.menu_main_title_02)
        )
        items.forEach { (id, icon, title) ->
            NavigationBarItem(
                selected = selectedItemId == id,
                onClick = { onSelected(id) },
                icon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp)) },
                label = { Text(stringResource(title), maxLines = 1) },
                alwaysShowLabel = true
            )
        }
    }
}
