package com.subtitleedit.player

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.R
import com.subtitleedit.EXTRA_REVEAL_MEDIA_PATH
import com.subtitleedit.MainActivity
import com.subtitleedit.library.PlayerTrackDetails
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.util.FavoriteDirectoryStore
import com.subtitleedit.ui.MediaCategory
import com.subtitleedit.ui.MediaCategoryRevealRequest
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.MediaInfoComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerTrackInfoDialog(
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onEditTags: () -> Unit,
    onNavigateToCategory: ((MediaCategoryRevealRequest) -> Unit)? = null,
    immersive: Boolean = true,
    onLocate: ((String) -> Unit)? = null
) {
    if (!state.showTrackInfoDialog) return
    val context = LocalContext.current
    val track = state.currentTrack
    val details = state.trackInfo
    val isVideo = details?.isVideo ?: (track?.isVideo == true)
    val unknownText = stringResource(R.string.player_info_unknown)
    var canNavigateCategories by remember(details?.filePath) { mutableStateOf(false) }
    LaunchedEffect(details?.filePath, onNavigateToCategory != null) {
        val path = details?.filePath ?: return@LaunchedEffect
        if (onNavigateToCategory == null) return@LaunchedEffect
        val repository = MediaLibraryRepository.getInstance(context)
        try {
            repository.revision.map {
                withContext(Dispatchers.IO) {
                    val roots = FavoriteDirectoryStore(context).load().mediaLibraryPaths
                    roots.any { path.startsWith(it.trimEnd('/') + "/") } && repository.isIndexedTrack(path)
                }
            }.distinctUntilChanged().collect { canNavigateCategories = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            canNavigateCategories = false
        }
    }
    val categoryNavigator = onNavigateToCategory?.takeIf { canNavigateCategories && details?.tagsAvailable == true }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !immersive)
    ) {
        if (immersive) PlayerPanelImmersiveWindow()
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 460.dp)
                .testTag("player-track-info-dialog").semantics { testTagsAsResourceId = true },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .heightIn(max = 620.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(if (isVideo) R.string.media_details_video_title else R.string.player_info_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()).testTag("player-track-info-fields"),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            val cover = details?.coverBitmap
                            if (cover != null) {
                                Image(
                                    bitmap = cover.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.matchParentSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    painter = painterResource(
                                        if (track?.isVideo == true) R.drawable.ic_file_video else R.drawable.ic_file_audio
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.player_info_file_name),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = details?.fileName ?: track?.name.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (state.trackInfoLoading && details == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                        }
                    } else if (details != null) {
                        TrackInfoLocationRow(
                            label = stringResource(R.string.player_info_location),
                            value = details.locationDisplay.ifBlank { details.filePath },
                            onLocate = {
                                onDismiss()
                                if (onLocate != null) onLocate(details.filePath)
                                else context.startActivity(Intent(context, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    putExtra(EXTRA_REVEAL_MEDIA_PATH, details.filePath)
                                })
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        TrackInfoField(
                            label = R.string.media_convert_info_genre,
                            value = details.genre.ifBlank { unknownText },
                            showActionIcon = true,
                            actionTag = "player-info-open:GENRES",
                            onActionClick = categoryNavigator?.let { navigate ->
                                { navigate(MediaCategoryRevealRequest(MediaCategory.GENRES, details.filePath)) }
                            }
                        )
                        TrackInfoField(
                            label = R.string.media_convert_info_title,
                            value = details.title.ifBlank { unknownText }
                        )
                        TrackInfoField(
                            label = if (isVideo) R.string.media_convert_info_author else R.string.media_convert_info_artist,
                            value = (if (isVideo) details.author else details.artist).ifBlank { unknownText },
                            showActionIcon = true,
                            actionTag = "player-info-open:SINGERS",
                            onActionClick = categoryNavigator?.let { navigate ->
                                { navigate(MediaCategoryRevealRequest(MediaCategory.SINGERS, details.filePath)) }
                            }
                        )
                        TrackInfoField(
                            label = R.string.media_convert_info_album,
                            value = details.album.ifBlank { unknownText },
                            showActionIcon = true,
                            actionTag = "player-info-open:ALBUMS",
                            onActionClick = categoryNavigator?.let { navigate ->
                                { navigate(MediaCategoryRevealRequest(MediaCategory.ALBUMS, details.filePath)) }
                            }
                        )
                        TrackInfoField(
                            label = R.string.media_convert_info_year,
                            value = details.year.ifBlank { unknownText }
                        )
                        TrackInfoField(
                            label = R.string.media_convert_info_album_artist,
                            value = details.albumArtist.ifBlank { unknownText },
                            showActionIcon = true,
                            actionTag = "player-info-open:ARTISTS",
                            onActionClick = categoryNavigator?.let { navigate ->
                                { navigate(MediaCategoryRevealRequest(MediaCategory.ARTISTS, details.filePath)) }
                            }
                        )
                        TrackInfoField(
                            label = R.string.media_convert_info_composer,
                            value = details.composer.ifBlank { unknownText },
                            showActionIcon = true,
                            actionTag = "player-info-open:COMPOSERS",
                            onActionClick = categoryNavigator?.let { navigate ->
                                { navigate(MediaCategoryRevealRequest(MediaCategory.COMPOSERS, details.filePath)) }
                            }
                        )
                        TrackInfoField(
                            label = R.string.media_convert_info_track_number,
                            value = details.trackNumber.ifBlank { "0" }
                        )
                        TrackInfoField(
                            label = R.string.media_convert_info_disc_number,
                            value = details.discNumber.ifBlank { "1" }
                        )
                        TrackInfoField(
                            label = R.string.media_convert_info_comment,
                            value = details.comment.ifBlank { unknownText },
                            dialogWindow = { if (immersive) PlayerPanelImmersiveWindow() }
                        )
                        TrackInfoField(
                            label = R.string.media_convert_info_lyricist,
                            value = details.lyricist.ifBlank { unknownText }
                        )
                        TrackInfoField(
                            label = R.string.media_convert_info_copyright,
                            value = details.copyright.ifBlank { unknownText }
                        )
                        TrackInfoField(
                            label = R.string.media_convert_info_language,
                            value = details.language.ifBlank { unknownText }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        TrackInfoField(R.string.player_info_duration, details.durationText.ifBlank { unknownText })
                        details.technicalDetails.forEach { detail -> TrackInfoField(detail.label, detail.value) }
                        TrackInfoField(R.string.player_info_file_size, details.fileSizeText.ifBlank { unknownText })
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onEditTags()
                        },
                        enabled = details?.canEditTags ?: (track != null)
                    ) {
                        Text(
                            text = stringResource(R.string.player_info_edit_tags),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.player_info_done),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackInfoLocationRow(
    label: String,
    value: String,
    onLocate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .testTag("player-info-open-location")
            .clickable(onClickLabel = stringResource(R.string.media_selection_locate_directory), onClick = onLocate)
            .padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                painter = painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrackInfoField(
    @androidx.annotation.StringRes label: Int,
    value: String,
    showActionIcon: Boolean = false,
    onActionClick: (() -> Unit)? = null,
    actionTag: String? = null,
    dialogWindow: @Composable () -> Unit = {}
) {
    val labelText = stringResource(label)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = labelText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (showActionIcon && onActionClick != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_open_in_new),
                    contentDescription = stringResource(R.string.player_info_open_category, labelText),
                    modifier = Modifier
                        .size(16.dp)
                        .then(if (actionTag != null) Modifier.testTag(actionTag) else Modifier)
                        .clip(CircleShape)
                        .clickable(onClick = onActionClick),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        val style = MaterialTheme.typography.bodyMedium
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        if (label == R.string.media_convert_info_comment) MediaInfoComment(value, style, color, dialogWindow = dialogWindow)
        else Text(value, style = style, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerPlaylistPickerDialog(
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onCreatePlaylist: (String, (Long?) -> Unit) -> Unit,
    onConfirmAdd: (Set<Long>) -> Unit
) {
    if (!state.showPlaylistPickerDialog) return
    var selectedIds by remember(state.currentTrack?.path) {
        mutableStateOf(state.trackContainedPlaylistIds)
    }
    LaunchedEffect(state.trackContainedPlaylistIds) {
        selectedIds = selectedIds + state.trackContainedPlaylistIds
    }
    var showCreateDialog by remember { mutableStateOf(false) }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        PlayerPanelImmersiveWindow()
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 420.dp)
                .testTag("player-playlist-picker-dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_action_add_to_playlist),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))

                if (state.userPlaylists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.player_playlist_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(state.userPlaylists, key = { it.id }) { playlist ->
                            val checked = playlist.id in selectedIds
                            val alreadyIn = playlist.id in state.trackContainedPlaylistIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (!alreadyIn) {
                                            selectedIds = if (checked) selectedIds - playlist.id else selectedIds + playlist.id
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (checked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHighest
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(if (checked) R.drawable.ic_check else R.drawable.ic_add),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (checked) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (playlist.isSystem) Text(
                                        text = stringResource(R.string.media_lib_system_playlist),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.player_playlist_picker_count, playlist.itemCount),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (alreadyIn) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showCreateDialog = true },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.player_playlist_picker_new))
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    val targetToAdd = selectedIds - state.trackContainedPlaylistIds
                    TextButton(
                        onClick = { onConfirmAdd(targetToAdd) },
                        enabled = targetToAdd.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.player_playlist_picker_add))
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        var creating by remember { mutableStateOf(false) }
        AppDialog(
            onDismissRequest = { showCreateDialog = false },
            properties = DialogProperties(decorFitsSystemWindows = false),
            title = { Text(stringResource(R.string.player_playlist_picker_new_title)) },
            text = {
                PlayerPanelImmersiveWindow()
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text(stringResource(R.string.player_playlist_picker_new_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        creating = true
                        onCreatePlaylist(newName.trim()) { createdId ->
                            creating = false
                            if (createdId != null) {
                                selectedIds = selectedIds + createdId
                                showCreateDialog = false
                            }
                        }
                    },
                    enabled = newName.trim().isNotBlank() && !creating
                ) {
                    Text(stringResource(R.string.player_playlist_picker_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
