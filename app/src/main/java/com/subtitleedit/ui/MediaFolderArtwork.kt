package com.subtitleedit.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.subtitleedit.R
import com.subtitleedit.feature.ui.directoryImageOverview
import com.subtitleedit.library.MediaFolderOrder
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.library.findMediaFolderArtwork
import com.subtitleedit.library.mediaFolderArtworkCandidates
import com.subtitleedit.nativebridge.DirectoryImageDecoder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val folderArtworkPermits = Semaphore(2)

@Composable
internal fun MediaFolderArtwork(
    folder: File,
    hasMedia: Boolean,
    order: MediaFolderOrder,
    refreshKey: Any,
    active: Boolean,
    targetSize: Int,
    modifier: Modifier
) {
    val artwork = if (hasMedia) rememberMediaFolderArtwork(folder, order, refreshKey, active, targetSize) else null
    MediaFolderArtworkTile(artwork, modifier, showDisc = hasMedia)
}

@Composable
internal fun rememberMediaFolderHeaderArtwork(
    folderStack: List<String>,
    order: MediaFolderOrder,
    refreshKey: Any,
    active: Boolean,
    targetSize: Int
): Bitmap? {
    var headerArtwork: Bitmap? = null
    for (path in folderStack) {
        val artwork = key(path) {
            val folder = remember { File(path) }
            rememberMediaFolderArtwork(folder, order, refreshKey, active, targetSize)
        }
        if (artwork != null) headerArtwork = artwork
    }
    return headerArtwork
}

@Composable
internal fun rememberMediaFolderArtwork(
    folder: File,
    order: MediaFolderOrder,
    refreshKey: Any,
    active: Boolean,
    targetSize: Int
): Bitmap? {
    val context = LocalContext.current
    val cacheDir = remember(context) { context.cacheDir }
    var artwork by remember(folder, cacheDir, targetSize) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(folder, order, refreshKey, active, cacheDir, targetSize) {
        if (active) artwork = folderArtworkPermits.withPermit {
            withContext(Dispatchers.IO) {
                val candidates = mediaFolderArtworkCandidates(folder, order, checkCancelled = { ensureActive() })
                findMediaFolderArtwork(
                    candidates,
                    readImage = { decodeFolderImage(it.file, cacheDir, targetSize) },
                    readMediaArtwork = {
                        ensureActive()
                        SharedMediaCache.artwork(it, targetSize, allowVideoFrame = false)
                    }
                )
            }
        }
    }
    return artwork
}

@Composable
internal fun MediaFolderArtworkTile(artwork: Bitmap?, modifier: Modifier, showDisc: Boolean = true) {
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (artwork != null) {
            Image(artwork.asImageBitmap(), null, Modifier.fillMaxSize().testTag("folder-artwork-image"),
                contentScale = ContentScale.Crop)
        } else {
            Icon(
                painterResource(if (showDisc) R.drawable.ic_nav_album else R.drawable.ic_folder), null,
                modifier = Modifier.fillMaxSize(0.5f).testTag(if (showDisc) "folder-artwork-disc" else "folder-artwork-icon"),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

private suspend fun decodeFolderImage(file: File, cacheDir: File, targetSize: Int): Bitmap? = try {
    val source = DirectoryImageDecoder.open(file, cacheDir)
    if (source == null) null else try {
        val region = directoryImageOverview(source.size, targetSize)
        source.decode(region.bounds, region.sample)?.let { bitmap ->
            if (source.quarterTurns == 0 && !source.mirrorHorizontally) bitmap else {
                val matrix = Matrix().apply {
                    if (source.mirrorHorizontally) setScale(-1f, 1f)
                    postRotate(source.quarterTurns * 90f)
                }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        }
    } finally {
        source.close()
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
