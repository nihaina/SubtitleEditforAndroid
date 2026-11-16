package com.subtitleedit.player

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.SharedFileCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Covers share the browser cache; a video's ordinary frame is not an embedded cover. */
@Composable
internal fun PlayerPlaylistArtwork(
    track: PlayerTrack,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    val targetSize = with(LocalDensity.current) { 40.dp.roundToPx().coerceIn(32, 256) }
    var artwork by remember(track.path, targetSize) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(track.path, targetSize) { mutableStateOf(false) }
    LaunchedEffect(track.path, targetSize) {
        SharedFileCache.attributes.invalidationRevision
            .map { withContext(Dispatchers.IO) { SharedFileCache.attributes.get(track.file) } }
            .distinctUntilChangedBy { it?.cacheKey }
            .collectLatest { metadata ->
                artwork = withContext(Dispatchers.IO) {
                    try {
                        metadata?.let { SharedMediaCache.artwork(it, targetSize, allowVideoFrame = false) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
                loaded = true
            }
    }
    Box(modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        val cover = artwork
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag("player-playlist-cover:${track.path}"),
                contentScale = ContentScale.Crop
            )
        } else if (loaded) {
            Icon(
                painter = painterResource(if (track.isVideo) R.drawable.ic_file_video else R.drawable.ic_file_audio),
                contentDescription = null,
                modifier = Modifier.size(22.dp)
                    .testTag("player-playlist-${if (track.isVideo) "video" else "audio"}-icon:${track.path}"),
                tint = if (highlighted) PlayerColors.Accent else PlayerColors.OnDarkVariant
            )
        }
    }
}
