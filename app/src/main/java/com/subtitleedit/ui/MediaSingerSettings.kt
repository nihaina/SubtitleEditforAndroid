package com.subtitleedit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.SingerSortField
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.SettingsSwitchRow
import com.subtitleedit.ui.settings.settingsPageBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaSingerSettings(
    options: MediaSingerViewOptions,
    onChange: (MediaSingerViewOptions) -> Unit,
    onBack: () -> Unit
) {
    var choosingInfo by rememberSaveable { mutableStateOf(false) }
    var choosingAlbumSort by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(settingsPageBackground()).testTag("singer-settings-page")) {
        TopAppBar(
            title = { Text(stringResource(R.string.media_category_singers)) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("singer-settings-back")) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.tools_navigate_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsPageBackground()),
            windowInsets = WindowInsets(0, 0, 0, 0)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("singer-settings-list")
        ) {
            item {
                SingerPreferenceRow(
                    title = stringResource(R.string.singer_more_info),
                    value = stringResource(options.moreInfo.labelRes),
                    onClick = { choosingInfo = true },
                    modifier = Modifier.testTag("singer-settings-more-info")
                )
            }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.singer_group_tracks),
                    checked = options.groupTracks,
                    onCheckedChange = { onChange(options.copy(groupTracks = it)) },
                    modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 64.dp)
                        .testTag("singer-settings-group-tracks")
                )
            }
            item {
                SingerPreferenceRow(
                    title = stringResource(R.string.singer_album_sort),
                    value = stringResource(options.albumSortField.singerAlbumLabelRes) + " · " +
                        stringResource(if (options.albumAscending) R.string.media_album_sort_ascending else R.string.media_album_sort_descending),
                    onClick = { choosingAlbumSort = true },
                    modifier = Modifier.testTag("singer-settings-album-sort")
                )
            }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.media_album_quick_search),
                    checked = options.quickSearch,
                    onCheckedChange = { onChange(options.copy(quickSearch = it)) },
                    modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 64.dp)
                        .testTag("singer-settings-quick-search")
                )
            }
        }
    }
    if (choosingInfo) SingerInfoChoiceDialog(
        selected = options.moreInfo,
        onSelected = { onChange(options.copy(moreInfo = it)); choosingInfo = false },
        onDismiss = { choosingInfo = false }
    )
    if (choosingAlbumSort) MediaAlbumSortDialog(
        sortField = options.albumSortField,
        ascending = options.albumAscending,
        onChange = { field, ascending -> onChange(options.copy(albumSortField = field, albumAscending = ascending)) },
        onDismiss = { choosingAlbumSort = false }
    )
}

@Composable
private fun SingerPreferenceRow(title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SingerInfoChoiceDialog(
    selected: SingerMoreInfo,
    onSelected: (SingerMoreInfo) -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.singer_more_info)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().selectableGroup().testTag("singer-more-info-list")) {
                items(SingerMoreInfo.entries, key = { it.name }) { info ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = selected == info, role = Role.RadioButton, onClick = { onSelected(info) })
                            .testTag("singer-more-info:${info.name}").padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(selected = selected == info, onClick = null)
                        Text(stringResource(info.labelRes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
internal fun MediaSingerSortDialog(
    sortField: SingerSortField,
    ascending: Boolean,
    onChange: (SingerSortField, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var draftField by rememberSaveable(sortField, ascending) { mutableStateOf(sortField) }
    var draftAscending by rememberSaveable(sortField, ascending) { mutableStateOf(ascending) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.singer_sort_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.media_album_sort_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(Modifier.fillMaxWidth().selectableGroup().testTag("singer-sort-list")) {
                    items(SingerSortField.entries, key = { it.name }) { field ->
                        val selected = field == draftField
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                    if (selected) draftAscending = !draftAscending else draftField = field
                                }).testTag("singer-sort:${field.name}").padding(end = 8.dp),
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onChange(draftField, draftAscending); onDismiss() },
                modifier = Modifier.testTag("singer-sort-confirm")
            ) { Text(stringResource(R.string.media_album_sort_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("singer-sort-cancel")) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private val SingerMoreInfo.labelRes: Int
    get() = when (this) {
        SingerMoreInfo.AUTO -> R.string.singer_more_info_auto
        SingerMoreInfo.NONE -> R.string.singer_more_info_none
        SingerMoreInfo.TRACK_COUNT -> R.string.media_album_sort_count
        SingerMoreInfo.DURATION -> R.string.media_album_sort_duration
        SingerMoreInfo.UPDATED_AT -> R.string.singer_more_info_updated
        SingerMoreInfo.YEAR -> R.string.singer_more_info_year
    }

private val SingerSortField.labelRes: Int
    get() = when (this) {
        SingerSortField.NAME -> R.string.media_album_sort_name
        SingerSortField.TRACK_COUNT -> R.string.media_album_sort_count
        SingerSortField.DURATION -> R.string.media_album_sort_duration
        SingerSortField.UPDATED_AT -> R.string.media_album_sort_updated
    }

private val AlbumSortField.singerAlbumLabelRes: Int
    get() = when (this) {
        AlbumSortField.NAME -> R.string.media_album_sort_name
        AlbumSortField.TRACK_COUNT -> R.string.media_album_sort_count
        AlbumSortField.DURATION -> R.string.media_album_sort_duration
        AlbumSortField.YEAR -> R.string.media_album_sort_year
        AlbumSortField.UPDATED_AT -> R.string.media_album_sort_updated
        AlbumSortField.ARTIST -> R.string.media_album_sort_artist
    }
