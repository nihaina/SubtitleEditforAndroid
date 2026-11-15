package com.subtitleedit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppLazyListScrollbar
import com.subtitleedit.ui.components.SettingsSwitchRow
import com.subtitleedit.ui.settings.settingsPageBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaAlbumSettings(
    options: MediaAlbumViewOptions,
    onChange: (MediaAlbumViewOptions) -> Unit,
    onBack: () -> Unit
) {
    var choosingView by remember { mutableStateOf(false) }
    var choosingSize by remember { mutableStateOf(false) }
    val gridSizeEnabled = options.viewMode == AlbumViewMode.GRID
    LaunchedEffect(gridSizeEnabled) {
        if (!gridSizeEnabled) choosingSize = false
    }
    Column(Modifier.fillMaxSize().background(settingsPageBackground()).testTag("album-settings-page")) {
        TopAppBar(
            title = { Text(stringResource(R.string.media_album_settings_title)) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("album-settings-back")) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.tools_navigate_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsPageBackground()),
            windowInsets = WindowInsets(0, 0, 0, 0)
        )
        Box(Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("album-settings-list")
            ) {
                item {
                    AlbumPreferenceRow(
                        title = stringResource(R.string.media_album_view_mode),
                        value = stringResource(options.viewMode.labelRes),
                        modifier = Modifier.testTag("album-settings-view-mode"),
                        onClick = { choosingView = true }
                    )
                }
                item {
                    AlbumPreferenceRow(
                        title = stringResource(R.string.media_album_grid_size),
                        value = stringResource(options.gridSize.labelRes),
                        modifier = Modifier.testTag("album-settings-grid-size"),
                        enabled = gridSizeEnabled,
                        onClick = { choosingSize = true }
                    )
                }
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.media_album_more_info),
                        checked = options.showMoreInfo,
                        onCheckedChange = { onChange(options.copy(showMoreInfo = it)) },
                        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 64.dp)
                            .testTag("album-settings-more-info")
                    )
                }
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.media_album_group_tracks),
                        checked = options.groupTracks,
                        onCheckedChange = { onChange(options.copy(groupTracks = it)) },
                        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 64.dp)
                            .testTag("album-settings-group-tracks")
                    )
                }
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.media_album_quick_search),
                        checked = options.quickSearch,
                        onCheckedChange = { onChange(options.copy(quickSearch = it)) },
                        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 64.dp)
                            .testTag("album-settings-quick-search")
                    )
                }
            }
            Box(Modifier.matchParentSize()) {
                AppLazyListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
    if (choosingView) {
        AlbumChoiceDialog(
            title = stringResource(R.string.media_album_view_mode),
            choices = AlbumViewMode.entries,
            selected = options.viewMode,
            label = { stringResource(it.labelRes) },
            onSelected = { onChange(options.copy(viewMode = it)); choosingView = false },
            onDismiss = { choosingView = false }
        )
    }
    if (choosingSize && gridSizeEnabled) {
        AlbumChoiceDialog(
            title = stringResource(R.string.media_album_grid_size),
            choices = AlbumGridSize.entries,
            selected = options.gridSize,
            label = { stringResource(it.labelRes) },
            onSelected = { onChange(options.copy(gridSize = it)); choosingSize = false },
            onDismiss = { choosingSize = false }
        )
    }
}

@Composable
private fun AlbumPreferenceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MediaAlbumSortDialog(
    sortField: AlbumSortField,
    ascending: Boolean,
    onChange: (AlbumSortField, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var draftField by rememberSaveable(sortField, ascending) { mutableStateOf(sortField) }
    var draftAscending by rememberSaveable(sortField, ascending) { mutableStateOf(ascending) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_album_sort_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.media_album_sort_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Box(Modifier.fillMaxWidth()) {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().selectableGroup().testTag("album-sort-list")
                    ) {
                        items(AlbumSortField.entries, key = { it.name }) { field ->
                            val selected = field == draftField
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                        if (selected) draftAscending = !draftAscending
                                        else draftField = field
                                    }).testTag("album-sort:${field.name}").padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(stringResource(field.labelRes), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                if (selected) {
                                    Icon(
                                        painterResource(R.drawable.ic_sort_direction), contentDescription = null,
                                        modifier = Modifier.size(20.dp).rotate(if (draftAscending) 0f else 180f),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        stringResource(if (draftAscending) R.string.media_album_sort_ascending else R.string.media_album_sort_descending),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.matchParentSize()) {
                        AppLazyListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onChange(draftField, draftAscending); onDismiss() },
                modifier = Modifier.testTag("album-sort-confirm")
            ) { Text(stringResource(R.string.media_album_sort_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("album-sort-cancel")) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun <T> AlbumChoiceDialog(
    title: String,
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth()) {
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().selectableGroup()) {
                    items(choices) { value ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelected(value) })
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Text(label(value), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Box(Modifier.matchParentSize()) {
                    AppLazyListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

internal val AlbumViewMode.labelRes: Int
    get() = when (this) {
        AlbumViewMode.LIST_NO_ART -> R.string.media_album_view_no_art
        AlbumViewMode.LIST_ART -> R.string.media_album_view_art
        AlbumViewMode.GRID -> R.string.media_album_view_grid
    }

internal val AlbumGridSize.labelRes: Int
    get() = when (this) {
        AlbumGridSize.SMALL -> R.string.media_album_grid_small
        AlbumGridSize.MEDIUM -> R.string.media_album_grid_medium
        AlbumGridSize.LARGE -> R.string.media_album_grid_large
    }

private val AlbumSortField.labelRes: Int
    get() = when (this) {
        AlbumSortField.NAME -> R.string.media_album_sort_name
        AlbumSortField.TRACK_COUNT -> R.string.media_album_sort_count
        AlbumSortField.DURATION -> R.string.media_album_sort_duration
        AlbumSortField.YEAR -> R.string.media_album_sort_year
        AlbumSortField.UPDATED_AT -> R.string.media_album_sort_updated
        AlbumSortField.ARTIST -> R.string.media_album_sort_artist
    }
