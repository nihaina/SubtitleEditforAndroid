package com.subtitleedit.feature.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import com.subtitleedit.R
import com.subtitleedit.nativebridge.DirectoryImageDecoder
import com.subtitleedit.ui.components.AppTopBar
import java.io.File
import kotlinx.coroutines.CancellationException

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
    modifier: Modifier = Modifier
) {
    val isSelectionActive = selectionTitle != null
    val barColor = if (isSelectionActive) MaterialTheme.colorScheme.surfaceContainer else Color.Black
    val textColor = if (isSelectionActive) MaterialTheme.colorScheme.onSurface else Color.White
    val pageIndex = files.indexOf(file)
    val positionTitle = if (files.size > 1) {
        stringResource(R.string.directory_image_preview_position, pageIndex + 1, files.size)
    } else null
    val heading = selectionTitle ?: positionTitle
    val selectFileLabel = stringResource(R.string.select_file)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier.background(Color.Black)) {
        Box(Modifier.fillMaxWidth().height(TopAppBarDefaults.TopAppBarExpandedHeight)) {
            if (isSelectionActive || controlsVisible) {
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
                                color = if (!isSelectionActive && positionTitle != null) Color.LightGray else textColor,
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
                            colors = CheckboxDefaults.colors(uncheckedColor = Color.White)
                        )
                    }
                )
            }
        }
        key(files) {
            val pager = rememberPagerState(initialPage = pageIndex) { files.size }
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
                modifier = Modifier.weight(1f).fillMaxSize()
            ) { page ->
                val pageFile = files[page]
                DirectoryImagePreviewPage(
                    file = pageFile,
                    quarterTurns = if (pageFile == file) quarterTurns else 0,
                    canPage = { delta ->
                        val movingForward = if (isRtl) delta > 0f else delta < 0f
                        if (movingForward) page < files.lastIndex else page > 0
                    },
                    onTap = onToggleControls.takeIf { pageFile == file && !isSelectionActive }
                )
            }
        }
    }
}

@Composable
private fun DirectoryImagePreviewPage(file: File, quarterTurns: Int, canPage: (Float) -> Boolean, onTap: (() -> Unit)?) {
    val cacheDir = LocalContext.current.cacheDir
    val result by produceState<Result<Bitmap?>?>(initialValue = null, file, cacheDir) {
        value = runCatching { DirectoryImageDecoder.decode(file, cacheDir) }.onFailure {
            if (it is CancellationException) throw it
        }
    }
    val bitmap = result?.getOrNull()
    val handleTap by rememberUpdatedState(onTap)
    val tapModifier = if (bitmap != null || onTap == null) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures { handleTap?.invoke() }
    }
    Box(Modifier.fillMaxSize().then(tapModifier), contentAlignment = Alignment.Center) {
        when {
            result == null -> CircularProgressIndicator()
            bitmap != null -> DirectoryImageViewport(remember(bitmap) { bitmap.asImageBitmap() }, quarterTurns, canPage, onTap)
            else -> Text(stringResource(R.string.directory_image_preview_failed), color = Color.White)
        }
    }
}
