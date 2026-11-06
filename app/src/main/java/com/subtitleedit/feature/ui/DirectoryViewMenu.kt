package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField

@Composable
internal fun DirectoryViewMenu(
    mode: FileBrowserViewMode,
    sortField: FileSortField,
    sortDirection: FileSortDirection,
    onModeChanged: (FileBrowserViewMode) -> Unit,
    onSortFieldChanged: (FileSortField) -> Unit,
    onSortDirectionChanged: (FileSortDirection) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val menuWidth = (LocalConfiguration.current.screenWidthDp - 24).coerceIn(240, 380).dp
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("directory-view-button")) {
            Icon(painterResource(viewModeIcon(mode)), stringResource(R.string.directory_view_options))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.width(menuWidth)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.directory_view_heading), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth()) {
                    for (view in FileBrowserViewMode.entries) {
                        ViewMenuChoice(stringResource(viewModeLabel(view)), viewModeIcon(view), mode == view,
                            Modifier.weight(1f).testTag("directory-view-${view.name}")) { onModeChanged(view) }
                    }
                }
                Text(stringResource(R.string.directory_sort_heading), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth()) {
                    for ((field, label, icon) in listOf(
                        Triple(FileSortField.NAME, R.string.directory_sort_name, R.drawable.ic_sort_name),
                        Triple(FileSortField.SIZE, R.string.directory_sort_size, R.drawable.ic_batch_rename_sort),
                        Triple(FileSortField.DATE, R.string.directory_sort_date, R.drawable.ic_sort_date),
                        Triple(FileSortField.TYPE, R.string.directory_sort_type, R.drawable.ic_file)
                    )) {
                        ViewMenuChoice(stringResource(label), icon, sortField == field,
                            Modifier.weight(1f).testTag("directory-sort-${field.name}")) { onSortFieldChanged(field) }
                    }
                    val direction = stringResource(if (sortDirection == FileSortDirection.ASCENDING) R.string.file_picker_sort_ascending else R.string.file_picker_sort_descending)
                    ViewMenuChoice(direction, R.drawable.ic_sort_direction, false,
                        Modifier.weight(1f).testTag("directory-sort-direction").semantics { stateDescription = direction }, Role.Button) {
                        onSortDirectionChanged(if (sortDirection == FileSortDirection.ASCENDING) FileSortDirection.DESCENDING else FileSortDirection.ASCENDING)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewMenuChoice(label: String, icon: Int, selected: Boolean, modifier: Modifier, role: Role = Role.RadioButton, onClick: () -> Unit) {
    Column(modifier.selectable(selected, role = role, onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(3.dp).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer))
        Text(label, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

private fun viewModeIcon(mode: FileBrowserViewMode): Int = when (mode) {
    FileBrowserViewMode.DETAILED -> R.drawable.ic_view_detailed
    FileBrowserViewMode.COMPACT -> R.drawable.ic_view_compact
    FileBrowserViewMode.GRID -> R.drawable.ic_view_grid
}

private fun viewModeLabel(mode: FileBrowserViewMode): Int = when (mode) {
    FileBrowserViewMode.DETAILED -> R.string.directory_view_detailed
    FileBrowserViewMode.COMPACT -> R.string.directory_view_compact
    FileBrowserViewMode.GRID -> R.string.directory_view_grid
}
