package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaConvertCommand
import com.subtitleedit.MediaConvertVideoFilter
import com.subtitleedit.MediaConvertVideoSettings
import com.subtitleedit.MediaConvertVideoSettingsSupport
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.util.ByteSizeFormat

@Composable
internal fun MediaConvertVideoSettingDialog(
    setting: MediaConvertVideoSetting, settings: MediaConvertVideoSettings, codec: String,
    pixels: List<String>, sourcePixelFormat: String?, multipleSources: Boolean,
    onDismiss: () -> Unit, onConfirm: (MediaConvertVideoSettings) -> Unit,
    sourceFileSizeBytes: Long? = null
) {
    val off = stringResource(R.string.media_convert_setting_off)
    val default = stringResource(R.string.media_convert_setting_default)
    var range: ClosedFloatingPointRange<Double>? = null
    var decimals = false
    val initial: String
    val options: List<AppOption<String>>
    val hint: String
    var details: Map<String, String> = emptyMap()
    when (setting) {
        MediaConvertVideoSetting.FRAME_RATE -> {
            initial = settings.frameRate
            val rates = if (codec in setOf("mpeg1video", "mpeg2video")) MediaConvertVideoSettingsSupport.mpegRates else MediaConvertVideoSettingsSupport.frameRates
            options = listOf(AppOption("", stringResource(R.string.media_convert_frame_rate_original))) + rates.map { AppOption(it, "${MediaConvertVideoSettingsSupport.rateLabel(it)} fps") }
            range = 1.0..240.0
            decimals = true
            hint = stringResource(R.string.media_convert_frame_rate_hint)
        }
        MediaConvertVideoSetting.ROTATION, MediaConvertVideoSetting.DEINTERLACE,
        MediaConvertVideoSetting.FLIP_HORIZONTAL, MediaConvertVideoSetting.FLIP_VERTICAL, MediaConvertVideoSetting.TWO_PASS -> {
            error("Dropdown video settings use the inline option selector")
        }
        MediaConvertVideoSetting.CRF -> {
            initial = settings.crf?.toString().orEmpty()
            val limits = MediaConvertCommand.qualityRange(codec)
            range = limits.first.toDouble()..limits.last.toDouble()
            val presets = listOf(40, 35, 34, 33, 32, 31, 30, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18).filter { it in limits }
            options = listOf(AppOption("", stringResource(R.string.media_convert_crf_none))) + presets.map { AppOption(it.toString(), it.toString()) }
            details = presets.mapNotNull { value ->
                val label = when (value) {
                    40 -> R.string.media_convert_crf_smallest
                    in 22..27 -> R.string.media_convert_crf_recommended
                    18 -> R.string.media_convert_crf_best
                    else -> return@mapNotNull null
                }
                value.toString() to stringResource(label)
            }.toMap()
            hint = stringResource(R.string.media_convert_crf_hint, limits.first, limits.last)
        }
        MediaConvertVideoSetting.SIZE -> {
            initial = settings.sizeLimitPercent?.toString().orEmpty()
            options = listOf(AppOption("", stringResource(R.string.media_convert_size_none))) + MediaConvertVideoSettingsSupport.sizePercents.map { AppOption(it.toString(), "$it%") }
            details = MediaConvertVideoSettingsSupport.sizePercents.associate { percent ->
                percent.toString() to (MediaConvertVideoSettingsSupport.targetSizeBytes(sourceFileSizeBytes, percent)?.let { ByteSizeFormat.format(it) } ?: "—")
            }
            hint = stringResource(R.string.media_convert_size_hint) +
                if (multipleSources) "\n" + stringResource(R.string.media_convert_size_batch) else ""
        }
        MediaConvertVideoSetting.KEYFRAME -> {
            initial = settings.keyframeSeconds
            range = 0.1..600.0
            decimals = true
            options = listOf(AppOption("", default)) + listOf(1, 2, 3, 5, 10).map { AppOption(it.toString(), stringResource(R.string.media_convert_seconds, it.toString())) }
            hint = stringResource(R.string.media_convert_keyframe_hint)
        }
        MediaConvertVideoSetting.FILTER -> {
            initial = settings.filter.name
            options = MediaConvertVideoFilter.entries.map { AppOption(it.name, stringResource(videoFilterLabel(it))) }
            hint = stringResource(R.string.media_convert_video_filter_hint)
        }
        MediaConvertVideoSetting.PIXEL_FORMAT -> {
            initial = settings.pixelFormat
            options = listOf(AppOption("auto", stringResource(R.string.media_convert_pixel_auto)),
                AppOption("original", sourcePixelFormat?.let { "$it (${stringResource(R.string.media_convert_resolution_source)})" } ?: stringResource(R.string.media_convert_pixel_original))) + pixels.map { AppOption(it, it) }
            hint = stringResource(R.string.media_convert_pixel_hint) + if (multipleSources) "\n" + stringResource(R.string.media_convert_pixel_batch) else ""
        }
    }
    MediaConvertVideoValueDialog(stringResource(setting.title), initial, options, range, decimals, hint, details, onDismiss) { selected ->
        onConfirm(when (setting) {
            MediaConvertVideoSetting.FRAME_RATE -> settings.copy(frameRate = selected)
            MediaConvertVideoSetting.ROTATION, MediaConvertVideoSetting.DEINTERLACE,
            MediaConvertVideoSetting.FLIP_HORIZONTAL, MediaConvertVideoSetting.FLIP_VERTICAL, MediaConvertVideoSetting.TWO_PASS ->
                error("Dropdown video settings use the inline option selector")
            MediaConvertVideoSetting.CRF -> settings.copy(crf = selected.toIntOrNull())
            MediaConvertVideoSetting.SIZE -> settings.copy(sizeLimitPercent = selected.toIntOrNull())
            MediaConvertVideoSetting.KEYFRAME -> settings.copy(keyframeSeconds = selected)
            MediaConvertVideoSetting.FILTER -> settings.copy(filter = MediaConvertVideoFilter.valueOf(selected))
            MediaConvertVideoSetting.PIXEL_FORMAT -> settings.copy(pixelFormat = selected)
        })
    }
}

/** Every setting keeps a draft until Done; the scroll area leaves the bottom actions fixed. */
@Composable
private fun MediaConvertVideoValueDialog(
    title: String, initial: String, options: List<AppOption<String>>, range: ClosedFloatingPointRange<Double>?,
    decimals: Boolean, hint: String, details: Map<String, String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit
) {
    var selected by rememberSaveable { mutableStateOf(initial.takeIf { id -> options.any { it.id == id } } ?: if (range != null) "custom" else options.first().id) }
    var custom by rememberSaveable { mutableStateOf(initial.takeUnless { id -> options.any { it.id == id } }.orEmpty()) }
    val value = if (selected == "custom") custom else selected
    val valid = selected != "custom" || (custom.isNotBlank() &&
        (if (decimals) custom.toDoubleOrNull() else custom.toIntOrNull()?.toDouble())?.let { it.isFinite() && range?.contains(it) == true } == true)
    MediaConvertDialogSurface(onDismiss, heightFraction = 0.85f) {
        MediaConvertDialogTitle(title)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            items(options, key = { it.id }) { option ->
                VideoValueChoice(option.label, selected == option.id, Modifier.testTag("video-setting-option-${option.id}"), details[option.id].orEmpty()) { selected = option.id }
                HorizontalDivider()
            }
            if (range != null) item {
                VideoValueChoice(stringResource(R.string.media_convert_quality_custom), selected == "custom", Modifier.testTag("video-setting-option-custom")) { selected = "custom" }
                if (selected == "custom") OutlinedTextField(custom, onValueChange = { text ->
                    if (text.length <= 12 && text.all { it in '0'..'9' || decimals && it == '.' }) custom = text
                }, singleLine = true, isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
                    label = { Text(stringResource(R.string.media_convert_setting_custom_value)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("video-setting-custom"))
            }
            if (hint.isNotBlank()) item {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
        MediaConvertDialogActions(onDismiss, { onConfirm(value) }, valid, Modifier.testTag("video-setting-confirm"))
    }
}

@Composable
private fun VideoValueChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, detail: String = "", onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
