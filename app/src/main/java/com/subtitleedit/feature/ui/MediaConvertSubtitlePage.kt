package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaConvertSubtitleType
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.AppOptionSelector
import com.subtitleedit.ui.theme.AppSpacing

@Composable
internal fun MediaConvertSubtitlePage(
    singleSource: Boolean,
    enabled: Boolean,
    fileName: String?,
    type: MediaConvertSubtitleType,
    allowedExtensions: Set<String>,
    copyingVideo: Boolean,
    burnInAvailable: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onTypeChanged: (MediaConvertSubtitleType) -> Unit
) {
    val editable = singleSource && enabled
    val types = listOf(
        AppOption(MediaConvertSubtitleType.BURN_IN, stringResource(R.string.media_convert_subtitle_burn)),
        AppOption(MediaConvertSubtitleType.SOFT, stringResource(R.string.media_convert_subtitle_soft))
    )
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
            Text(stringResource(R.string.media_convert_subtitle_add), Modifier.width(AppSpacing.FormLabelWidth), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onPick, enabled = editable && allowedExtensions.isNotEmpty(),
                modifier = Modifier.weight(1f).testTag("convert-subtitle-pick")) {
                Text(fileName ?: stringResource(R.string.media_convert_subtitle_choose), Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.ic_arrow_right), null, Modifier.size(18.dp))
            }
            if (fileName != null) IconButton(onClick = onRemove, enabled = editable,
                modifier = Modifier.testTag("convert-subtitle-remove")) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.media_convert_subtitle_remove))
            }
        }
        AppOptionSelector(stringResource(R.string.media_convert_subtitle_type), types.first { it.id == type }, types,
            editable, onTypeChanged, Modifier.testTag("convert-subtitle-type"))
        val hint = when {
            !singleSource -> R.string.media_convert_subtitle_single_only
            type == MediaConvertSubtitleType.BURN_IN && copyingVideo -> R.string.media_convert_subtitle_copy_hint
            type == MediaConvertSubtitleType.BURN_IN && !burnInAvailable -> R.string.media_convert_subtitle_filter_missing
            allowedExtensions.isEmpty() -> R.string.media_convert_subtitle_container_unsupported
            type == MediaConvertSubtitleType.BURN_IN -> R.string.media_convert_subtitle_burn_hint
            else -> R.string.media_convert_subtitle_soft_hint
        }
        Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (singleSource && allowedExtensions.isNotEmpty()) Text(
            stringResource(R.string.media_convert_subtitle_formats, allowedExtensions.joinToString("、") { it.uppercase(java.util.Locale.ROOT) }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
