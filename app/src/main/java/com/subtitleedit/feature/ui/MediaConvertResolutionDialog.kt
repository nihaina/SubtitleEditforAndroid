package com.subtitleedit.feature.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.MediaConvertFitMode
import com.subtitleedit.MediaConvertResolution
import com.subtitleedit.MediaConvertResolutionSettings
import com.subtitleedit.MediaConvertVideoSize
import com.subtitleedit.R

@Composable
internal fun MediaConvertResolutionDialog(
    initial: MediaConvertResolutionSettings,
    sourceSize: MediaConvertVideoSize?,
    multipleSources: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (MediaConvertResolutionSettings) -> Unit
) {
    val initialSize = initial.size ?: sourceSize
    var index by rememberSaveable { mutableIntStateOf(initial.presetIndex) }
    var width by rememberSaveable { mutableStateOf((initial.customWidth ?: initialSize?.width)?.toString().orEmpty()) }
    var height by rememberSaveable { mutableStateOf((initial.customHeight ?: initialSize?.height)?.toString().orEmpty()) }
    var locked by rememberSaveable { mutableStateOf(initial.aspectLocked) }
    var ratio by rememberSaveable {
        mutableDoubleStateOf(
            if (initial.customWidth != null && initial.customHeight != null && initial.customWidth > 0 && initial.customHeight > 0)
                initial.customWidth.toDouble() / initial.customHeight
            else initialSize?.aspectRatio ?: 0.0
        )
    }
    var mode by rememberSaveable { mutableStateOf(initial.fitMode.name) }
    var showFitRules by rememberSaveable { mutableStateOf(false) }
    val settings = MediaConvertResolutionSettings(index, width.toIntOrNull(), height.toIntOrNull(), locked, MediaConvertFitMode.valueOf(mode))
    val widthError = index == MediaConvertResolution.CUSTOM && !MediaConvertResolution.validDimension(width.toIntOrNull())
    val heightError = index == MediaConvertResolution.CUSTOM && !MediaConvertResolution.validDimension(height.toIntOrNull())
    val lockLabel = stringResource(R.string.media_convert_resolution_lock)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.95f).fillMaxHeight(0.9f).imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Text(stringResource(R.string.media_convert_resolution_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { onConfirm(settings) }, enabled = settings.isValid, modifier = Modifier.testTag("resolution-confirm")) {
                        Text(stringResource(R.string.media_convert_resolution_done))
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    item {
                        Text(stringResource(R.string.media_convert_resolution_original), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp))
                        ResolutionChoice(
                            text = sourceSize?.label ?: stringResource(R.string.media_convert_resolution_original),
                            detail = stringResource(R.string.media_convert_resolution_source),
                            selected = index == 0,
                            onClick = { index = 0 }
                        )
                        if (multipleSources) Text(
                            stringResource(R.string.media_convert_resolution_batch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        HorizontalDivider()
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Text(stringResource(R.string.media_convert_resolution_custom), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.media_convert_resolution_lock), style = MaterialTheme.typography.labelMedium)
                            Switch(
                                checked = locked,
                                onCheckedChange = { checked ->
                                    val w = width.toDoubleOrNull()
                                    val h = height.toDoubleOrNull()
                                    ratio = if (w != null && h != null && w > 0 && h > 0) w / h else sourceSize?.aspectRatio ?: 0.0
                                    locked = checked && ratio > 0
                                },
                                enabled = (width.toIntOrNull()?.let { it > 0 } == true && height.toIntOrNull()?.let { it > 0 } == true) || sourceSize != null,
                                modifier = Modifier.padding(start = 4.dp).semantics { contentDescription = lockLabel }.testTag("resolution-lock")
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioButton(selected = index == MediaConvertResolution.CUSTOM, onClick = { index = MediaConvertResolution.CUSTOM })
                            OutlinedTextField(
                                value = width,
                                onValueChange = { value ->
                                    if (value.length <= 4 && value.all(Char::isDigit)) {
                                        width = value
                                        index = MediaConvertResolution.CUSTOM
                                        if (locked) height = MediaConvertResolution.linkedDimension(value, ratio.takeIf { it > 0 } ?: sourceSize?.aspectRatio ?: 0.0, true)
                                    }
                                },
                                label = { Text(stringResource(R.string.media_convert_resolution_width)) },
                                placeholder = { Text("2–4096") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                isError = widthError,
                                modifier = Modifier.weight(1f).testTag("resolution-width")
                            )
                            OutlinedTextField(
                                value = height,
                                onValueChange = { value ->
                                    if (value.length <= 4 && value.all(Char::isDigit)) {
                                        height = value
                                        index = MediaConvertResolution.CUSTOM
                                        if (locked) width = MediaConvertResolution.linkedDimension(value, ratio.takeIf { it > 0 } ?: sourceSize?.aspectRatio ?: 0.0, false)
                                    }
                                },
                                label = { Text(stringResource(R.string.media_convert_resolution_height)) },
                                placeholder = { Text("2–4096") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                isError = heightError,
                                modifier = Modifier.weight(1f).testTag("resolution-height")
                            )
                        }
                        Text(
                            stringResource(R.string.media_convert_resolution_limits),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (widthError || heightError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        HorizontalDivider()
                        Text(stringResource(R.string.media_convert_resolution_presets), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                    }
                    items(MediaConvertResolution.presets.withIndex().drop(1).sortedBy { if (it.value!!.height > it.value!!.width) 0 else 1 }, key = { it.index }) { preset ->
                        val size = preset.value!!
                        ResolutionChoice(
                            text = size.label,
                            detail = stringResource(if (size.height > size.width) R.string.media_convert_resolution_portrait else R.string.media_convert_resolution_landscape),
                            selected = index == preset.index,
                            onClick = { index = preset.index }
                        )
                        HorizontalDivider()
                    }
                }
                HorizontalDivider()
                TextButton(onClick = { showFitRules = true }, modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("resolution-fit-rules")) {
                    Text(stringResource(R.string.media_convert_resolution_fit_rule), modifier = Modifier.weight(1f))
                    Text(stringResource(fitModeTitle(settings.fitMode)))
                }
            }
        }
    }
    if (showFitRules) FitRuleDialog(
        initial = settings.fitMode,
        onDismiss = { showFitRules = false },
        onConfirm = { mode = it.name; showFitRules = false }
    )
}

@Composable
private fun ResolutionChoice(text: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fitModeTitle(mode: MediaConvertFitMode) = when (mode) {
    MediaConvertFitMode.FIT -> R.string.media_convert_fit_fit
    MediaConvertFitMode.FILL -> R.string.media_convert_fit_fill
    MediaConvertFitMode.STRETCH -> R.string.media_convert_fit_stretch
    MediaConvertFitMode.CENTER -> R.string.media_convert_fit_center
}

private fun fitModeDescription(mode: MediaConvertFitMode) = when (mode) {
    MediaConvertFitMode.FIT -> R.string.media_convert_fit_fit_description
    MediaConvertFitMode.FILL -> R.string.media_convert_fit_fill_description
    MediaConvertFitMode.STRETCH -> R.string.media_convert_fit_stretch_description
    MediaConvertFitMode.CENTER -> R.string.media_convert_fit_center_description
}

@Composable
private fun FitRuleDialog(initial: MediaConvertFitMode, onDismiss: () -> Unit, onConfirm: (MediaConvertFitMode) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(initial.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_convert_resolution_fit_rule)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MediaConvertFitMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(selected == mode.name, role = Role.RadioButton, onClick = { selected = mode.name }).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FitModeIcon(mode, selected == mode.name)
                        Column {
                            Text(stringResource(fitModeTitle(mode)), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(fitModeDescription(mode)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(MediaConvertFitMode.valueOf(selected)) }, modifier = Modifier.testTag("resolution-fit-confirm")) { Text(stringResource(R.string.media_convert_resolution_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun FitModeIcon(mode: MediaConvertFitMode, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(36.dp)) {
        val frame = Size(size.width, size.height * 0.8f)
        val top = (size.height - frame.height) / 2
        drawRect(Color.Black, Offset(0f, top), frame)
        val picture = when (mode) {
            MediaConvertFitMode.FIT -> Size(frame.width, frame.height * 0.6f)
            MediaConvertFitMode.CENTER -> Size(frame.width * 0.6f, frame.height * 0.6f)
            else -> frame
        }
        drawRect(color.copy(alpha = 0.6f), Offset((frame.width - picture.width) / 2, top + (frame.height - picture.height) / 2), picture)
        drawRect(color, Offset(0f, top), frame, style = Stroke(2.dp.toPx()))
    }
}
