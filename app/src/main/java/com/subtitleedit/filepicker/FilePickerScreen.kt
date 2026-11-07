package com.subtitleedit.filepicker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.feature.ui.DirectoryBreadcrumbBar
import com.subtitleedit.feature.ui.DirectoryToolbarTitle
import com.subtitleedit.feature.ui.BrowserFileRow
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppTopBar
import com.subtitleedit.util.FileOperationUiPolicy
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilePickerScreen(
    state: FilePickerUiState,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onPermission: () -> Unit,
    onNavigate: (File) -> Boolean,
    onFileClick: (File) -> Unit,
    onSearch: (String) -> Unit,
    onSort: (FileSortField, FileSortDirection) -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onFilterUnsupported: (Boolean) -> Unit,
    onNameChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectRange: () -> Unit,
    onRefresh: () -> Unit,
    onCreateDirectory: (String, (String?) -> Unit) -> Unit,
    onOverwrite: () -> Unit,
    onRename: () -> Unit,
    onDismissOverwrite: () -> Unit
) {
    var searchExpanded by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var sortExpanded by rememberSaveable { mutableStateOf(false) }
    var locationsExpanded by rememberSaveable { mutableStateOf(false) }
    var createExpanded by rememberSaveable { mutableStateOf(false) }
    val selectionActive = state.mode == FilePickerMode.OPEN_FILES && state.selectedPaths.isNotEmpty()
    val searchVisible = searchExpanded && !selectionActive && state.hasStoragePermission
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val closeSearch: () -> Unit = { searchExpanded = false; onSearch("") }
    val toolbarBack: () -> Unit = {
        when {
            selectionActive -> onClearSelection()
            searchVisible -> closeSearch()
            else -> onBack()
        }
    }
    BackHandler(onBack = toolbarBack)
    LaunchedEffect(searchVisible) {
        if (searchVisible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }
    val defaultTitle = stringResource(when (state.mode) {
        FilePickerMode.OPEN_FILE, FilePickerMode.OPEN_FILES -> R.string.file_picker_open_file
        FilePickerMode.DIRECTORY -> R.string.file_picker_select_directory
        FilePickerMode.SAVE_FILE -> R.string.save_file
    })
    val title = if (selectionActive) FileOperationUiPolicy.selectionTitle(null, state.selectedPaths.size)
        else defaultTitle
    Scaffold(
        topBar = {
            AppTopBar(
                title = title,
                titleContent = {
                    DirectoryToolbarTitle(
                        searchOpen = searchVisible,
                        query = state.query,
                        title = title,
                        onQueryChanged = onSearch,
                        onCloseSearch = closeSearch,
                        focusRequester = searchFocusRequester
                    )
                },
                navigationIcon = {
                    IconButton(onClick = toolbarBack) {
                        Icon(painterResource(if (selectionActive || searchVisible) R.drawable.ic_close else R.drawable.ic_back),
                            contentDescription = stringResource(if (selectionActive || searchVisible) R.string.cancel
                                else R.string.tools_navigate_back))
                    }
                },
                actions = {
                    if (selectionActive) {
                        IconButton(onClick = onSelectAll) {
                            Icon(painterResource(R.drawable.ic_select_all),
                                stringResource(R.string.source_selection_select_all))
                        }
                        IconButton(onClick = onSelectRange) {
                            Icon(painterResource(R.drawable.ic_select_range), "局部全选")
                        }
                    } else if (state.hasStoragePermission) {
                        if (!searchVisible) {
                            IconButton(onClick = { searchExpanded = true }) {
                                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.menu_search))
                            }
                        }
                        IconButton(onClick = { locationsExpanded = true }) {
                            Icon(painterResource(R.drawable.ic_nav_folder), stringResource(R.string.file_picker_locations))
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(painterResource(R.drawable.ic_more_vertical), stringResource(R.string.file_picker_more))
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.file_picker_sort)) },
                                    onClick = { menuExpanded = false; sortExpanded = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.file_picker_create_directory)) },
                                    onClick = { menuExpanded = false; createExpanded = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.file_picker_show_hidden)) },
                                    trailingIcon = { Checkbox(state.showHidden, null) },
                                    onClick = { onShowHidden(!state.showHidden); menuExpanded = false })
                                DropdownMenuItem(text = { Text(stringResource(R.string.file_picker_filter_unsupported)) },
                                    trailingIcon = { Checkbox(state.filterUnsupported, null) },
                                    onClick = { onFilterUnsupported(!state.filterUnsupported); menuExpanded = false })
                                DropdownMenuItem(text = { Text(stringResource(R.string.file_picker_refresh)) },
                                    onClick = { onRefresh(); menuExpanded = false })
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (state.mode == FilePickerMode.SAVE_FILE && state.hasStoragePermission) {
                        OutlinedTextField(
                            value = state.suggestedName,
                            onValueChange = onNameChanged,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.file_picker_file_name)) },
                            singleLine = true,
                            isError = state.error != null,
                            supportingText = state.error?.let { message -> { Text(message) } }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                        if (state.mode != FilePickerMode.OPEN_FILE) {
                            Button(onClick = onConfirm,
                                enabled = state.hasStoragePermission &&
                                    (state.mode != FilePickerMode.OPEN_FILES || state.selectedPaths.isNotEmpty())) {
                                Text(when (state.mode) {
                                    FilePickerMode.OPEN_FILES -> stringResource(R.string.file_picker_confirm_count, state.selectedPaths.size)
                                    FilePickerMode.DIRECTORY -> stringResource(R.string.file_picker_use_directory)
                                    else -> stringResource(R.string.save)
                                })
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!state.hasStoragePermission) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.file_picker_permission_message), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onPermission, modifier = Modifier.padding(top = 20.dp)) {
                        Text(stringResource(R.string.file_picker_grant_permission))
                    }
                }
            } else {
                DirectoryBreadcrumbBar(directory = state.directory, enabled = true, onNavigate = onNavigate)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val listState = rememberLazyListState()
                LaunchedEffect(state.directory.path, state.query, state.sortField, state.sortDirection) {
                    listState.scrollToItem(0)
                }
                if (state.files.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        if (state.loading) CircularProgressIndicator()
                        else Text(state.error ?: stringResource(R.string.file_picker_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                        contentPadding = PaddingValues(8.dp)) {
                        items(state.files, key = { it.absolutePath }) { file ->
                            val supported = file.absolutePath !in state.unsupportedPaths
                            BrowserFileRow(
                                file = file,
                                relativePathRoot = null,
                                isSelected = supported && file.absolutePath in state.selectedPaths,
                                isSelectionMode = selectionActive,
                                onClick = { onFileClick(file) },
                                onLongClick = { onFileClick(file) },
                                enabled = supported
                            )
                        }
                    }
                }
            }
        }
    }
    if (sortExpanded) PickerSortDialog(state.sortField, state.sortDirection,
        onDismiss = { sortExpanded = false }, onSort = onSort)
    if (locationsExpanded) PickerLocationsDialog(state.locations,
        onDismiss = { locationsExpanded = false }, onNavigate = { file ->
            if (onNavigate(file)) locationsExpanded = false
        })
    if (createExpanded) CreateDirectoryDialog(onDismiss = { createExpanded = false }, onCreate = onCreateDirectory)
    state.pendingOverwrite?.let { target ->
        AppConflictDialog(message = stringResource(R.string.file_picker_overwrite_message, target.name),
            onOverwrite = onOverwrite, onRename = onRename, onCancel = onDismissOverwrite)
    }
}

@Composable
private fun PickerSortDialog(field: FileSortField, direction: FileSortDirection,
    onDismiss: () -> Unit, onSort: (FileSortField, FileSortDirection) -> Unit) {
    AppDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.file_picker_sort)) },
        text = {
            Column {
                listOf(FileSortField.NAME to R.string.batch_rename_sort_name,
                    FileSortField.TYPE to R.string.batch_rename_sort_type,
                    FileSortField.SIZE to R.string.batch_rename_sort_size,
                    FileSortField.DATE to R.string.batch_rename_sort_date).forEach { (value, title) ->
                    Row(Modifier.fillMaxWidth().clickable { onSort(value, direction) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(value == field, onClick = { onSort(value, direction) })
                        Text(stringResource(title))
                    }
                }
                HorizontalDivider()
                listOf(FileSortDirection.ASCENDING to R.string.file_picker_sort_ascending,
                    FileSortDirection.DESCENDING to R.string.file_picker_sort_descending).forEach { (value, title) ->
                    Row(Modifier.fillMaxWidth().clickable { onSort(field, value) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(value == direction, onClick = { onSort(field, value) })
                        Text(stringResource(title))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) } })
}

@Composable
private fun PickerLocationsDialog(locations: List<FilePickerLocation>, onDismiss: () -> Unit,
    onNavigate: (File) -> Unit) {
    AppDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.file_picker_locations)) },
        text = {
            LazyColumn {
                items(locations, key = { it.directory.path }) { location ->
                    Row(Modifier.fillMaxWidth().clickable { onNavigate(location.directory) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_nav_folder), null, Modifier.size(28.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(location.label, style = MaterialTheme.typography.bodyLarge)
                            Text(location.directory.path, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun CreateDirectoryDialog(onDismiss: () -> Unit, onCreate: (String, (String?) -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AppDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_picker_create_directory)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it; error = null }, singleLine = true,
                label = { Text(stringResource(R.string.file_picker_directory_name)) }, isError = error != null,
                supportingText = error?.let { message -> { Text(message) } })
        },
        confirmButton = { TextButton(onClick = { onCreate(name) { message ->
            if (message == null) onDismiss() else error = message
        } }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
