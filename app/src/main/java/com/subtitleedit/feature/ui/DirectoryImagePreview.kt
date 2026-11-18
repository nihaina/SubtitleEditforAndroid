package com.subtitleedit.feature.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppTopBar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DirectoryImagePreview(
    file: File,
    isSelected: Boolean,
    selectionTitle: String?,
    onNavigationClick: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val barColor = if (isSelected) MaterialTheme.colorScheme.surfaceContainer else Color.Black
    val textColor = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.White
    val selectFileLabel = stringResource(R.string.select_file)
    Column(modifier.background(Color.Black)) {
        AppTopBar(
            title = file.name,
            containerColor = barColor,
            scrolledContainerColor = barColor,
            navigationIcon = {
                IconButton(onClick = onNavigationClick) {
                    Icon(
                        painterResource(if (isSelected) R.drawable.ic_close else R.drawable.ic_back),
                        contentDescription = stringResource(
                            if (isSelected) R.string.file_picker_clear_selection else R.string.tools_navigate_back
                        ),
                        tint = textColor
                    )
                }
            },
            titleContent = {
                Column {
                    if (selectionTitle != null) {
                        Text(selectionTitle, color = textColor, style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        file.name,
                        color = textColor,
                        style = if (isSelected) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
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
        Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
            val windowSize = LocalWindowInfo.current.containerSize
            key(file) {
                val result by produceState<Result<Bitmap?>?>(initialValue = null, windowSize) {
                    if (windowSize.width <= 0 || windowSize.height <= 0) return@produceState
                    value = withContext(Dispatchers.IO) {
                        runCatching { decodePreviewBitmap(file, windowSize.width, windowSize.height) }
                    }
                }
                val bitmap = result?.getOrNull()
                when {
                    result == null -> CircularProgressIndicator()
                    bitmap != null -> Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.directory_image_preview),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    else -> Text(stringResource(R.string.directory_image_preview_failed), color = Color.White)
                }
            }
        }
    }
}

private fun decodePreviewBitmap(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
    fun scale(width: Int, height: Int): Float = minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val factor = scale(width, height)
            if (factor < 1f) {
                decoder.setTargetSize(
                    (width * factor).roundToInt().coerceAtLeast(1),
                    (height * factor).roundToInt().coerceAtLeast(1)
                )
            }
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null
    val sample = Integer.highestOneBit((1f / scale(width, height)).toInt())
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}
