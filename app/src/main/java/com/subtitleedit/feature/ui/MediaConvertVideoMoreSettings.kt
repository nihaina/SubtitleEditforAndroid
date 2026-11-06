package com.subtitleedit.feature.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaConvertBitrateMode
import com.subtitleedit.MediaConvertDeinterlace
import com.subtitleedit.MediaConvertRotation
import com.subtitleedit.MediaConvertVideoFilter
import com.subtitleedit.MediaConvertVideoSettings
import com.subtitleedit.MediaConvertVideoSettingsSupport
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.AppOptionSelector
import com.subtitleedit.ui.theme.AppMotion
import com.subtitleedit.ui.theme.AppSpacing

internal enum class MediaConvertVideoSetting(val title: Int, val dropdown: Boolean = false) {
    FRAME_RATE(R.string.media_convert_frame_rate), ROTATION(R.string.media_convert_rotation, true),
    FLIP_HORIZONTAL(R.string.media_convert_flip_horizontal, true), FLIP_VERTICAL(R.string.media_convert_flip_vertical, true),
    CRF(R.string.media_convert_crf), SIZE(R.string.media_convert_size_limit), TWO_PASS(R.string.media_convert_two_pass, true),
    KEYFRAME(R.string.media_convert_keyframe_interval), DEINTERLACE(R.string.media_convert_deinterlace, true),
    FILTER(R.string.media_convert_video_filter), PIXEL_FORMAT(R.string.media_convert_pixel_format)
}

@Composable
internal fun MediaConvertVideoMoreSettings(
    settings: MediaConvertVideoSettings,
    expanded: Boolean,
    codec: String,
    bitrateMode: MediaConvertBitrateMode,
    detectedPixelFormats: List<String>?,
    sourcePixelFormat: String?,
    multipleSources: Boolean,
    isConverting: Boolean,
    onToggle: () -> Unit,
    onChange: (MediaConvertVideoSettings) -> Unit,
    sourceFileSizeBytes: Long? = null
) {
    var dialog by rememberSaveable { mutableStateOf<MediaConvertVideoSetting?>(null) }
    val enabled = !isConverting && codec != "copy"
    val hasTargetBitrate = bitrateMode != MediaConvertBitrateMode.AUTO || settings.sizeLimitPercent != null
    val pixels = MediaConvertVideoSettingsSupport.supportedPixels(codec, detectedPixelFormats)
    AppSection {
        val rotation by animateFloatAsState(if (expanded) 90f else 0f, AppMotion.fast(), label = "video-more-chevron")
        val description = stringResource(if (expanded) R.string.media_convert_expanded else R.string.media_convert_collapsed)
        TextButton(onClick = onToggle, enabled = !isConverting,
            modifier = Modifier.fillMaxWidth().testTag("video-more-toggle").semantics { stateDescription = description }) {
            Text(stringResource(R.string.media_convert_more_settings), modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Icon(painterResource(R.drawable.ic_arrow_right), null, Modifier.size(18.dp).graphicsLayer(rotationZ = rotation))
        }
        AnimatedVisibility(expanded,
            enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
            exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                for (setting in MediaConvertVideoSetting.entries) {
                    val applicable = when (setting) {
                        MediaConvertVideoSetting.CRF -> codec in MediaConvertVideoSettingsSupport.crfCodecs && !hasTargetBitrate
                        MediaConvertVideoSetting.TWO_PASS -> codec in MediaConvertVideoSettingsSupport.twoPassCodecs && hasTargetBitrate
                        else -> true
                    }
                    val inactive = !applicable && (setting == MediaConvertVideoSetting.CRF && settings.crf != null ||
                        setting == MediaConvertVideoSetting.TWO_PASS && settings.twoPass)
                    val value = videoSettingLabel(setting, settings, pixels)
                    val label = if (inactive) stringResource(R.string.media_convert_setting_inactive, value) else value
                    val editable = enabled && applicable
                    if (setting.dropdown) {
                        val selected = when (setting) {
                            MediaConvertVideoSetting.ROTATION -> settings.rotation.name
                            MediaConvertVideoSetting.DEINTERLACE -> settings.deinterlace.name
                            MediaConvertVideoSetting.FLIP_HORIZONTAL -> settings.flipHorizontal.toString()
                            MediaConvertVideoSetting.FLIP_VERTICAL -> settings.flipVertical.toString()
                            else -> settings.twoPass.toString()
                        }
                        val options = when (setting) {
                            MediaConvertVideoSetting.ROTATION -> listOf(
                                AppOption(MediaConvertRotation.NONE.name, stringResource(R.string.media_convert_setting_off)),
                                AppOption(MediaConvertRotation.CLOCKWISE.name, stringResource(R.string.media_convert_rotate_clockwise)),
                                AppOption(MediaConvertRotation.HALF.name, stringResource(R.string.media_convert_rotate_half)),
                                AppOption(MediaConvertRotation.COUNTERCLOCKWISE.name, stringResource(R.string.media_convert_rotate_counterclockwise)))
                            MediaConvertVideoSetting.DEINTERLACE -> listOf(
                                AppOption(MediaConvertDeinterlace.OFF.name, stringResource(R.string.media_convert_setting_off)),
                                AppOption(MediaConvertDeinterlace.AUTO.name, stringResource(R.string.media_convert_deinterlace_auto)),
                                AppOption(MediaConvertDeinterlace.ON.name, stringResource(R.string.media_convert_deinterlace_all)))
                            else -> listOf(AppOption("false", stringResource(R.string.media_convert_setting_off)),
                                AppOption("true", stringResource(R.string.media_convert_setting_on)))
                        }
                        AppOptionSelector(stringResource(setting.title), AppOption(selected, label), options,
                            editable, onSelected = { next ->
                                onChange(when (setting) {
                                    MediaConvertVideoSetting.ROTATION -> settings.copy(rotation = MediaConvertRotation.valueOf(next))
                                    MediaConvertVideoSetting.DEINTERLACE -> settings.copy(deinterlace = MediaConvertDeinterlace.valueOf(next))
                                    MediaConvertVideoSetting.FLIP_HORIZONTAL -> settings.copy(flipHorizontal = next.toBoolean())
                                    MediaConvertVideoSetting.FLIP_VERTICAL -> settings.copy(flipVertical = next.toBoolean())
                                    else -> settings.copy(twoPass = next.toBoolean())
                                })
                            }, modifier = Modifier.testTag("video-more-${setting.name}").semantics { if (!editable) disabled() })
                    } else {
                        VideoSettingRow(setting, label, editable) { dialog = setting }
                    }
                }
                Text(stringResource(R.string.media_convert_video_more_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasTargetBitrate) Text(stringResource(R.string.media_convert_video_rate_control_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val selected = dialog
    if (selected != null && enabled) {
        MediaConvertVideoSettingDialog(selected, settings, codec, pixels, sourcePixelFormat, multipleSources,
            onDismiss = { dialog = null }, onConfirm = { onChange(it); dialog = null }, sourceFileSizeBytes = sourceFileSizeBytes)
    }
}

@Composable
private fun VideoSettingRow(setting: MediaConvertVideoSetting, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
        Text(stringResource(setting.title), Modifier.width(AppSpacing.FormLabelWidth), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick, enabled = enabled, modifier = Modifier.weight(1f).testTag("video-more-${setting.name}")) {
            Text(value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(painterResource(R.drawable.ic_arrow_right), null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun videoSettingLabel(setting: MediaConvertVideoSetting, value: MediaConvertVideoSettings, pixels: List<String>): String {
    val off = stringResource(R.string.media_convert_setting_off)
    return when (setting) {
        MediaConvertVideoSetting.FRAME_RATE -> value.frameRate.takeIf { it.isNotBlank() }?.let { "${MediaConvertVideoSettingsSupport.rateLabel(it)} fps" }
            ?: stringResource(R.string.media_convert_frame_rate_original)
        MediaConvertVideoSetting.ROTATION -> stringResource(when (value.rotation) {
            MediaConvertRotation.NONE -> R.string.media_convert_setting_off
            MediaConvertRotation.CLOCKWISE -> R.string.media_convert_rotate_clockwise
            MediaConvertRotation.HALF -> R.string.media_convert_rotate_half
            MediaConvertRotation.COUNTERCLOCKWISE -> R.string.media_convert_rotate_counterclockwise
        })
        MediaConvertVideoSetting.FLIP_HORIZONTAL -> if (value.flipHorizontal) stringResource(R.string.media_convert_setting_on) else off
        MediaConvertVideoSetting.FLIP_VERTICAL -> if (value.flipVertical) stringResource(R.string.media_convert_setting_on) else off
        MediaConvertVideoSetting.CRF -> value.crf?.toString() ?: stringResource(R.string.media_convert_crf_none)
        MediaConvertVideoSetting.SIZE -> value.sizeLimitPercent?.let { "$it%" } ?: stringResource(R.string.media_convert_size_none)
        MediaConvertVideoSetting.TWO_PASS -> if (value.twoPass) stringResource(R.string.media_convert_setting_on) else off
        MediaConvertVideoSetting.KEYFRAME -> value.keyframeSeconds.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.media_convert_seconds, it) }
            ?: stringResource(R.string.media_convert_setting_default)
        MediaConvertVideoSetting.DEINTERLACE -> stringResource(when (value.deinterlace) {
            MediaConvertDeinterlace.OFF -> R.string.media_convert_setting_off
            MediaConvertDeinterlace.AUTO -> R.string.media_convert_deinterlace_auto
            MediaConvertDeinterlace.ON -> R.string.media_convert_deinterlace_all
        })
        MediaConvertVideoSetting.FILTER -> stringResource(videoFilterLabel(value.filter))
        MediaConvertVideoSetting.PIXEL_FORMAT -> when (value.pixelFormat) {
            "auto" -> stringResource(R.string.media_convert_pixel_auto)
            "original" -> stringResource(R.string.media_convert_pixel_original)
            in pixels -> value.pixelFormat
            else -> stringResource(R.string.media_convert_pixel_adapted)
        }
    }
}

internal fun videoFilterLabel(filter: MediaConvertVideoFilter): Int = when (filter) {
    MediaConvertVideoFilter.NONE -> R.string.media_convert_setting_off
    MediaConvertVideoFilter.GRAYSCALE -> R.string.media_convert_filter_grayscale
    MediaConvertVideoFilter.YELLOW -> R.string.media_convert_filter_yellow
    MediaConvertVideoFilter.RED -> R.string.media_convert_filter_red
    MediaConvertVideoFilter.GREEN -> R.string.media_convert_filter_green
    MediaConvertVideoFilter.BLUE -> R.string.media_convert_filter_blue
    MediaConvertVideoFilter.GOLD -> R.string.media_convert_filter_gold
}
