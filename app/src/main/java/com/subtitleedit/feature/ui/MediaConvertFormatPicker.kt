package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppChoiceTile
import com.subtitleedit.ui.settings.SettingsTileRow
import com.subtitleedit.ui.theme.AppSpacing

@Composable
internal fun MediaConvertFormatPicker(
    title: String,
    formats: List<MediaFormatOption>,
    selectedFormat: String?,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = if (formats.isNotEmpty() && formats.all { it.isAudioOnly }) {
        R.drawable.ic_file_audio
    } else {
        R.drawable.ic_file_video
    }
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val selection = formats.firstOrNull { it.extension == selectedFormat }?.displayName
    SettingsTileRow(
        title = title,
        modifier = modifier,
        iconRes = iconRes,
        value = selection,
        valueBelow = false,
        showArrow = true,
        enabled = enabled && formats.isNotEmpty(),
        onClick = { showDialog = true }
    )
    if (showDialog && enabled && formats.isNotEmpty()) {
        MediaConvertFormatDialog(
            title = title,
            formats = formats,
            initialFormat = selectedFormat,
            onDismiss = { showDialog = false },
            onConfirm = { onSelected(it); showDialog = false }
        )
    }
}

@Composable
private fun MediaConvertFormatDialog(
    title: String,
    formats: List<MediaFormatOption>,
    initialFormat: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selectedFormat by rememberSaveable {
        mutableStateOf(initialFormat?.takeIf { initial -> formats.any { it.extension == initial } })
    }
    val validSelection = selectedFormat?.takeIf { selected -> formats.any { it.extension == selected } }
    MediaConvertDialogSurface(onDismiss, heightFraction = 0.75f) {
        MediaConvertDialogTitle(title)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(64.dp),
            modifier = Modifier.weight(1f).testTag("format-picker-grid"),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
        ) {
            items(formats, key = { it.extension }) { format ->
                AppChoiceTile(
                    text = format.displayName,
                    selected = selectedFormat == format.extension,
                    onClick = { selectedFormat = format.extension },
                    modifier = Modifier.testTag("format-option-${format.extension}").semantics {
                        selected = selectedFormat == format.extension
                        role = Role.RadioButton
                    },
                    singleLine = true,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)
                )
            }
        }
        MediaConvertDialogActions(
            onDismiss = onDismiss,
            onConfirm = { validSelection?.let(onConfirm) },
            canConfirm = validSelection != null,
            confirmModifier = Modifier.testTag("format-picker-confirm")
        )
    }
}
