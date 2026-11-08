package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.AppOptionSelector

@Composable
internal fun MediaConvertMetadataDialog(
    metadata: MediaConvertMetadata,
    onDismiss: () -> Unit,
    onConfirm: (MediaConvertMetadata) -> Unit
) {
    var title by rememberSaveable(metadata.title) { mutableStateOf(metadata.title) }
    var author by rememberSaveable(metadata.author) { mutableStateOf(metadata.author) }
    var comment by rememberSaveable(metadata.comment) { mutableStateOf(metadata.comment) }
    var cover by rememberSaveable(metadata.cover) { mutableStateOf(metadata.cover) }
    val coverOptions = listOf(
        AppOption(MediaConvertCoverMode.DEFAULT, stringResource(R.string.media_convert_info_cover_default)),
        AppOption(MediaConvertCoverMode.NONE, stringResource(R.string.media_convert_info_cover_none))
    )

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_convert_info_settings)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppOptionSelector(
                    label = stringResource(R.string.media_convert_info_cover),
                    value = coverOptions.first { it.id == cover },
                    options = coverOptions,
                    onSelected = { cover = it },
                    modifier = Modifier.testTag("convert-metadata-cover")
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.media_convert_info_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("convert-metadata-title")
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.media_convert_info_author)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("convert-metadata-author")
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.media_convert_info_comment)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("convert-metadata-comment")
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(metadata.copy(cover = cover, title = title, author = author, comment = comment)) },
                modifier = Modifier.testTag("convert-metadata-confirm")) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("convert-metadata-cancel")) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
