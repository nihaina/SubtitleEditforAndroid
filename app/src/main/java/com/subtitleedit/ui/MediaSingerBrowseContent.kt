package com.subtitleedit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppLazyListScrollbar
import com.subtitleedit.library.MediaSinger
import com.subtitleedit.library.MediaCreditRole
import com.subtitleedit.library.SingerSortField
import com.subtitleedit.player.formatPlaybackTime
import java.text.DateFormat
import java.util.Date

/** The singer overview uses the same catalog as albums, without loading artwork for text rows. */
@Composable
internal fun MediaSingerBrowseContent(
    singers: List<MediaSinger>,
    options: MediaSingerViewOptions,
    query: String,
    onQueryChanged: (String) -> Unit,
    onSort: () -> Unit,
    onSettings: () -> Unit,
    onOpenSinger: (MediaSinger) -> Unit,
    modifier: Modifier = Modifier,
    role: MediaCreditRole = MediaCreditRole.SINGER
) {
    val labels = role.labels
    val tagPrefix = role.tagPrefix
    val listState = rememberLazyListState()
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (options.quickSearch) {
                TextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.weight(1f).testTag("$tagPrefix-search"),
                    placeholder = { Text(stringResource(labels.search), maxLines = 1) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = onSort, modifier = Modifier.testTag("$tagPrefix-sort")) {
                Icon(painterResource(R.drawable.ic_album_sort), stringResource(labels.sort))
            }
            IconButton(onClick = onSettings, modifier = Modifier.testTag("$tagPrefix-settings")) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(labels.settings))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (singers.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (options.quickSearch && query.isNotBlank()) labels.noResults else labels.empty),
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("$tagPrefix-list"),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(singers, key = { it.key }) { singer ->
                        val info = singerMoreInfoText(singer, options)
                        Column(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenSinger(singer) }
                                .testTag("$tagPrefix-item:${singer.key}").heightIn(min = 56.dp)
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
                        ) {
                            Text(
                                singer.name.ifBlank { stringResource(role.unknownRes) },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (info != null) Text(
                                info,
                                modifier = Modifier.testTag("$tagPrefix-info:${singer.key}"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                AppLazyListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun singerMoreInfoText(singer: MediaSinger, options: MediaSingerViewOptions): String? {
    val info = if (options.moreInfo != SingerMoreInfo.AUTO) options.moreInfo else when (options.sortField) {
        SingerSortField.NAME -> SingerMoreInfo.NONE
        SingerSortField.TRACK_COUNT -> SingerMoreInfo.TRACK_COUNT
        SingerSortField.DURATION -> SingerMoreInfo.DURATION
        SingerSortField.UPDATED_AT -> SingerMoreInfo.UPDATED_AT
    }
    return when (info) {
        SingerMoreInfo.AUTO, SingerMoreInfo.NONE -> null
        SingerMoreInfo.TRACK_COUNT -> stringResource(R.string.media_album_detail_song_count, singer.tracks.size)
        SingerMoreInfo.DURATION -> formatPlaybackTime(singer.durationMs)
        SingerMoreInfo.UPDATED_AT -> if (singer.updatedAtMs > 0L) {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(singer.updatedAtMs))
        } else stringResource(R.string.singer_info_unknown)
        SingerMoreInfo.YEAR -> singer.year?.toString() ?: stringResource(R.string.singer_info_unknown)
    }
}
