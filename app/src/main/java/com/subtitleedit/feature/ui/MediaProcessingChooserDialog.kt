package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.subtitleedit.MediaProcessingPage
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.theme.AppSpacing

/** Page choices remain visible so unsupported directory selections are clearly disabled. */
@Composable
internal fun MediaProcessingChooserDialog(
    canConvert: Boolean,
    canMux: Boolean,
    onDismiss: () -> Unit,
    onSelect: (MediaProcessingPage) -> Unit
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_convert_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                TextButton(
                    onClick = { onSelect(MediaProcessingPage.CONVERT) },
                    enabled = canConvert,
                    modifier = Modifier.fillMaxWidth().testTag("media-processing-choice-convert")
                ) { Text(stringResource(R.string.media_processing_choice_convert)) }
                TextButton(
                    onClick = { onSelect(MediaProcessingPage.MUX) },
                    enabled = canMux,
                    modifier = Modifier.fillMaxWidth().testTag("media-processing-choice-mux")
                ) { Text(stringResource(R.string.media_processing_choice_mux)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
