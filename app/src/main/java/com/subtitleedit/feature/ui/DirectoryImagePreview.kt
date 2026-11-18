package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import com.subtitleedit.R
import com.subtitleedit.nativebridge.DirectoryImageDecoder
import com.subtitleedit.nativebridge.DirectoryImageSource
import com.subtitleedit.ui.components.AppTopBar
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DirectoryImagePreview(
    file: File,
    files: List<File>,
    isSelected: Boolean,
    selectionTitle: String?,
    controlsVisible: Boolean,
    quarterTurns: Int,
    onPageChanged: (File) -> Unit,
    onNavigationClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {}
) {
    val isSelectionActive = selectionTitle != null
    val barColor = directoryImageOverlayColor()
    val textColor = MaterialTheme.colorScheme.onSurface
    val pageIndex = files.indexOf(file)
    val positionTitle = if (files.size > 1) {
        stringResource(R.string.directory_image_preview_position, pageIndex + 1, files.size)
    } else null
    val heading = selectionTitle ?: positionTitle
    val selectFileLabel = stringResource(R.string.select_file)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.background(Color.Black)) {
        key(files) {
            val pager = rememberPagerState(initialPage = pageIndex) { files.size }
            val pagerScope = rememberCoroutineScope()
            val notifyPageChanged by rememberUpdatedState(onPageChanged)
            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }.collect { page ->
                    notifyPageChanged(files[page])
                }
            }
            HorizontalPager(
                state = pager,
                userScrollEnabled = files.size > 1,
                overscrollEffect = null,
                key = { files[it].absolutePath },
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val pageFile = files[page]
                val adjacentPage: (Float) -> Int = { delta ->
                    page + if ((delta < 0f) != isRtl) 1 else -1
                }
                DirectoryImagePreviewPage(
                    file = pageFile,
                    quarterTurns = if (pageFile == file) quarterTurns else 0,
                    canPage = { delta -> adjacentPage(delta) in files.indices },
                    onPageSwipe = { delta ->
                        val target = adjacentPage(delta)
                        if (pager.settledPage == page && !pager.isScrollInProgress && target in files.indices) {
                            pagerScope.launch { pager.animateScrollToPage(target) }
                        }
                    },
                    onTap = onToggleControls.takeIf { pageFile == file && !isSelectionActive }
                )
            }
        }
        if (isSelectionActive || controlsVisible) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().blockImagePreviewTaps()) {
                AppTopBar(
                    title = file.name,
                    containerColor = barColor,
                    scrolledContainerColor = barColor,
                    navigationIcon = {
                        IconButton(onClick = onNavigationClick) {
                            Icon(
                                painterResource(if (isSelectionActive) R.drawable.ic_close else R.drawable.ic_back),
                                contentDescription = stringResource(
                                    if (isSelectionActive) R.string.file_picker_clear_selection else R.string.tools_navigate_back
                                ),
                                tint = textColor
                            )
                        }
                    },
                    titleContent = {
                        Column {
                            if (heading != null) {
                                Text(heading, color = textColor, style = MaterialTheme.typography.titleLarge)
                            }
                            Text(
                                file.name,
                                color = if (heading != null) MaterialTheme.colorScheme.onSurfaceVariant else textColor,
                                style = if (heading != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelection() },
                            modifier = Modifier.semantics { contentDescription = selectFileLabel },
                            colors = CheckboxDefaults.colors(uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                )
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().blockImagePreviewTaps()) {
            bottomBar()
        }
    }
}

@Composable
internal fun directoryImageOverlayColor(): Color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f)

private fun Modifier.blockImagePreviewTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

@Composable
private fun DirectoryImagePreviewPage(
    file: File,
    quarterTurns: Int,
    canPage: (Float) -> Boolean,
    onPageSwipe: (Float) -> Unit,
    onTap: (() -> Unit)?
) {
    val cacheDir = LocalContext.current.cacheDir
    val windowSize = LocalWindowInfo.current.containerSize
    val targetEdge = maxOf(windowSize.width, windowSize.height).coerceIn(1024, 2048)
    val result by produceState<Result<DirectoryImageOverview>?>(initialValue = null, file, cacheDir) {
        var source: DirectoryImageSource? = null
        try {
            value = runCatching {
                withContext(Dispatchers.IO) {
                    val opened = checkNotNull(DirectoryImageDecoder.open(file, cacheDir))
                    source = opened
                    val region = directoryImageOverview(opened.size, targetEdge)
                    val image = checkNotNull(opened.decode(region.bounds, region.sample)).asImageBitmap()
                    DirectoryImageOverview(opened, image, region.sample)
                }
            }.onFailure { if (it is CancellationException) throw it }
            if (value?.isSuccess == true) awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { source?.close() }
        }
    }
    val overview = result?.getOrNull()
    val handleTap by rememberUpdatedState(onTap)
    val tapModifier = if (overview != null || onTap == null) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures { handleTap?.invoke() }
    }
    Box(Modifier.fillMaxSize().then(tapModifier), contentAlignment = Alignment.Center) {
        when {
            result == null -> CircularProgressIndicator()
            overview != null -> DirectoryImageViewport(
                image = overview.image,
                quarterTurns = quarterTurns + overview.source.quarterTurns,
                canPage = canPage,
                onTap = onTap,
                source = overview.source,
                overviewSample = overview.sample,
                onPageSwipe = onPageSwipe
            )
            else -> Text(stringResource(R.string.directory_image_preview_failed), color = Color.White)
        }
    }
}

private data class DirectoryImageOverview(
    val source: DirectoryImageSource,
    val image: ImageBitmap,
    val sample: Int
)
