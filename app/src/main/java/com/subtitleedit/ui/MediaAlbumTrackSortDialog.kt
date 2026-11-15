package com.subtitleedit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.library.AlbumTrackSortField
import com.subtitleedit.library.AlbumTrackSortOptions
import com.subtitleedit.ui.components.AppDialog

@Composable
internal fun MediaAlbumTrackSortDialog(
    options: AlbumTrackSortOptions,
    onConfirm: (AlbumTrackSortOptions) -> Unit,
    onDismiss: () -> Unit
) {
    var draftField by rememberSaveable(options.field, options.ascending) { mutableStateOf(options.field) }
    var draftAscending by rememberSaveable(options.field, options.ascending) { mutableStateOf(options.ascending) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_album_sort), contentDescription = null)
                Text(stringResource(R.string.media_album_track_sort_title))
            }
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.media_album_sort_hint),
                    modifier = Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup()
                        .testTag("album-track-sort-list")
                ) {
                    items(AlbumTrackSortField.entries, key = { it.name }) { field ->
                        val selected = field == draftField
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                    if (selected) draftAscending = !draftAscending else draftField = field
                                }).testTag("album-track-sort:${field.name}")
                                .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(
                                stringResource(field.labelRes), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
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
                onClick = { onConfirm(AlbumTrackSortOptions(draftField, draftAscending)); onDismiss() },
                modifier = Modifier.testTag("album-track-sort-confirm")
            ) { Text(stringResource(R.string.media_album_sort_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("album-track-sort-cancel")) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

internal val AlbumTrackSortField.labelRes: Int
    get() = when (this) {
        AlbumTrackSortField.TITLE -> R.string.media_album_track_sort_song_title
        AlbumTrackSortField.FILE_NAME -> R.string.media_album_track_sort_file_name
        AlbumTrackSortField.FOLDER_PATH -> R.string.media_album_track_sort_folder_path
        AlbumTrackSortField.ALBUM -> R.string.media_album_track_sort_album
        AlbumTrackSortField.ARTIST -> R.string.media_album_track_sort_artist
        AlbumTrackSortField.ALBUM_ARTIST -> R.string.media_album_track_sort_album_artist
        AlbumTrackSortField.COMPOSER -> R.string.media_album_track_sort_composer
        AlbumTrackSortField.GENRE -> R.string.media_album_track_sort_genre
        AlbumTrackSortField.TRACK_NUMBER -> R.string.media_album_track_sort_track_number
        AlbumTrackSortField.DURATION -> R.string.media_album_track_sort_duration
        AlbumTrackSortField.YEAR -> R.string.media_album_track_sort_year
        AlbumTrackSortField.MODIFIED_AT -> R.string.media_album_track_sort_modified
        AlbumTrackSortField.ADDED_AT -> R.string.media_album_track_sort_added
        AlbumTrackSortField.LAST_PLAYED -> R.string.media_album_track_sort_last_played
        AlbumTrackSortField.PLAY_COUNT -> R.string.media_album_track_sort_play_count
    }
