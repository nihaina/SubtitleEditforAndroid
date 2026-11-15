package com.subtitleedit.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.library.MediaAlbum
import com.subtitleedit.library.MediaArtworkLoader
import com.subtitleedit.library.mediaAlbumArtworkPaths
import com.subtitleedit.player.formatPlaybackTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Album overview only; its parent owns loading, search/sort policy and category navigation. */
@Composable
internal fun MediaAlbumBrowseContent(
    albums: List<MediaAlbum>,
    options: MediaAlbumViewOptions,
    query: String,
    onQueryChanged: (String) -> Unit,
    onSort: () -> Unit,
    onSettings: () -> Unit,
    onOpenAlbum: (MediaAlbum) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (options.quickSearch) {
                TextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.weight(1f).testTag("album-search"),
                    placeholder = { Text(stringResource(R.string.album_browse_search), maxLines = 1) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                    },
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
            IconButton(onClick = onSort, modifier = Modifier.testTag("album-sort")) {
                Icon(
                    painterResource(R.drawable.ic_album_sort),
                    contentDescription = stringResource(R.string.album_browse_sort)
                )
            }
            IconButton(onClick = onSettings, modifier = Modifier.testTag("album-settings")) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.album_browse_settings)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (albums.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(
                        if (query.isBlank()) R.string.album_browse_empty
                        else R.string.album_browse_no_results
                    ),
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (options.viewMode == AlbumViewMode.GRID) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // At phone width the reference sizes are four, three and two columns.
                // Wider windows add columns while retaining each size's visual density.
                val columns = (options.gridSize.compactColumns * (maxWidth.value / 360f).coerceAtLeast(1f)).toInt()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize().testTag("album-grid"),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(albums, key = { it.key }) { album ->
                        AlbumGridCard(album, options, onClick = { onOpenAlbum(album) })
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("album-list"),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(albums, key = { it.key }) { album ->
                    AlbumListRow(
                        album = album,
                        options = options,
                        showArtwork = options.viewMode == AlbumViewMode.LIST_ART,
                        showMoreInfo = options.showMoreInfo,
                        onClick = { onOpenAlbum(album) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumGridCard(album: MediaAlbum, options: MediaAlbumViewOptions, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("album-item:${album.key}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        SortedAlbumArtwork(album, options, Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                album.name.ifBlank { stringResource(R.string.album_browse_unknown) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (options.showMoreInfo) AlbumMoreInfo(album)
        }
    }
}

@Composable
private fun AlbumListRow(
    album: MediaAlbum,
    options: MediaAlbumViewOptions,
    showArtwork: Boolean,
    showMoreInfo: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("album-item:${album.key}")
            .clickable(onClick = onClick).heightIn(min = if (showArtwork) 76.dp else 56.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showArtwork) {
            SortedAlbumArtwork(album, options, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                album.name.ifBlank { stringResource(R.string.album_browse_unknown) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showMoreInfo) AlbumMoreInfo(album)
        }
    }
}

@Composable
private fun SortedAlbumArtwork(album: MediaAlbum, options: MediaAlbumViewOptions, modifier: Modifier) {
    val paths = remember(album, options.sortField, options.ascending) {
        mediaAlbumArtworkPaths(album, options.sortField, options.ascending)
    }
    MediaAlbumArtwork(paths, modifier, album.tracks.map { it.modifiedAtMs })
}

@Composable
private fun AlbumMoreInfo(album: MediaAlbum) {
    if (album.artist.isNotBlank()) {
        Text(
            album.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    val summary = stringResource(
        R.string.album_browse_summary,
        album.tracks.size,
        formatPlaybackTime(album.durationMs)
    )
    Text(
        if (album.year != null) "$summary · ${album.year}" else summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Artwork work is tied to visible cells, uses bounded decoding, and never blocks composition. */
@Composable
internal fun MediaAlbumArtwork(paths: List<String>, modifier: Modifier = Modifier, reloadKey: Any? = null) {
    BoxWithConstraints(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag("album-artwork"),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val targetSize = with(density) { maxWidth.toPx().toInt().coerceIn(32, 1024) }
        var cover by remember(paths, targetSize, reloadKey) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(paths, targetSize, reloadKey) {
            cover = withContext(Dispatchers.IO) {
                var found: Bitmap? = null
                for (path in paths.distinct()) {
                    currentCoroutineContext().ensureActive()
                    found = MediaArtworkLoader.loadCover(path, targetSize, allowVideoFrame = false)
                    if (found != null) break
                }
                found
            }
        }
        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_nav_album),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.5f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
        }
    }
}
