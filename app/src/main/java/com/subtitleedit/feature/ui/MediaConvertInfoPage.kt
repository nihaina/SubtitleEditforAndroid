package com.subtitleedit.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.R
import com.subtitleedit.ui.theme.AppSpacing

@Composable
internal fun MediaConvertInfoPage(
    enabled: Boolean,
    metadata: MediaConvertMetadata,
    onMetadataChanged: (MediaConvertMetadata) -> Unit
) {
    var showEditor by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner), modifier = Modifier.testTag("convert-info-page")) {
        Text(stringResource(R.string.media_convert_info_settings), style = MaterialTheme.typography.titleLarge)
        MediaConvertInfoRow(stringResource(R.string.media_convert_info_cover),
            stringResource(if (metadata.cover == MediaConvertCoverMode.DEFAULT) R.string.media_convert_info_cover_default else R.string.media_convert_info_cover_none),
            enabled, { showEditor = true }, "convert-info-cover")
        HorizontalDivider()
        MediaConvertInfoRow(stringResource(R.string.media_convert_info_title), metadata.title.ifBlank { stringResource(R.string.media_convert_info_empty) }, enabled, { showEditor = true }, "convert-info-title")
        HorizontalDivider()
        MediaConvertInfoRow(stringResource(R.string.media_convert_info_author), metadata.author.ifBlank { stringResource(R.string.media_convert_info_empty) }, enabled, { showEditor = true }, "convert-info-author")
        HorizontalDivider()
        MediaConvertInfoRow(stringResource(R.string.media_convert_info_comment), metadata.comment.ifBlank { stringResource(R.string.media_convert_info_empty) }, enabled, { showEditor = true }, "convert-info-comment")
        if (!enabled) Text(stringResource(R.string.media_convert_info_single_video_hint), modifier = Modifier.testTag("convert-info-single-video-hint"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showEditor && enabled) {
        MediaConvertMetadataDialog(
            metadata = metadata,
            onDismiss = { showEditor = false },
            onConfirm = { onMetadataChanged(it); showEditor = false }
        )
    }
}

@Composable
private fun MediaConvertInfoRow(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).testTag(tag).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(AppSpacing.FormLabelWidth), style = MaterialTheme.typography.bodyLarge)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
