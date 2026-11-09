package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaConvertSourceSubtitle
import com.subtitleedit.MediaConvertEmbeddedLyric
import com.subtitleedit.MediaConvertSubtitleType
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.AppOptionSelector
import com.subtitleedit.ui.theme.AppSpacing

@Composable
internal fun MediaConvertSubtitlePage(
    singleSource: Boolean,
    enabled: Boolean,
    fileNames: List<String>,
    type: MediaConvertSubtitleType,
    allowedExtensions: Set<String>,
    copyingVideo: Boolean,
    burnInAvailable: Boolean,
    onPick: () -> Unit,
    onRemove: (Int) -> Unit,
    onTypeChanged: (MediaConvertSubtitleType) -> Unit,
    isAudioOutput: Boolean = false,
    maxSubtitleFiles: Int = Int.MAX_VALUE,
    audioLyricsPlainText: Boolean = false,
    sourceSubtitleTracks: List<MediaConvertSourceSubtitle> = emptyList(),
    sourceSubtitleSupport: List<Boolean> = emptyList(),
    sourceSubtitleProbeFailed: Boolean = false,
    sourceSubtitleProbeInProgress: Boolean = false,
    outputFormatSelected: Boolean = true,
    sourceEmbeddedLyrics: List<MediaConvertEmbeddedLyric> = emptyList(),
    sourceLyricsProbeFailed: Boolean = false,
    sourceIsAudio: Boolean = false,
    onViewEmbeddedLyric: (Int) -> Unit = {}
) {
    val editable = singleSource && enabled && outputFormatSelected
    val types = listOf(
        AppOption(MediaConvertSubtitleType.BURN_IN, stringResource(R.string.media_convert_subtitle_burn)),
        AppOption(MediaConvertSubtitleType.SOFT, stringResource(R.string.media_convert_subtitle_soft))
    )
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
            Text(stringResource(if (isAudioOutput) R.string.media_convert_lyrics_add else R.string.media_convert_subtitle_add),
                Modifier.width(AppSpacing.FormLabelWidth), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onPick, enabled = editable && allowedExtensions.isNotEmpty(),
                modifier = Modifier.weight(1f).testTag("convert-subtitle-pick")) {
                Text(stringResource(if (isAudioOutput) R.string.media_convert_lyrics_choose else R.string.media_convert_subtitle_choose), Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.ic_arrow_right), null, Modifier.size(18.dp))
            }
        }
        fileNames.forEachIndexed { index, name ->
            Row(Modifier.fillMaxWidth().testTag("convert-subtitle-file-$index"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                Text(name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { onRemove(index) }, enabled = editable,
                    modifier = Modifier.testTag("convert-subtitle-remove-$index")) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.media_convert_subtitle_remove))
                }
            }
        }
        if (isAudioOutput) {
            Row(Modifier.fillMaxWidth().testTag("convert-subtitle-type"), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.media_convert_subtitle_type), Modifier.width(AppSpacing.FormLabelWidth),
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.media_convert_lyrics_embedded), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            AppOptionSelector(stringResource(R.string.media_convert_subtitle_type), types.first { it.id == type }, types,
                editable, onTypeChanged, Modifier.testTag("convert-subtitle-type"))
        }
        val hint = when {
            !singleSource -> R.string.media_convert_subtitle_single_only
            !outputFormatSelected -> R.string.media_convert_select_format_required
            isAudioOutput && allowedExtensions.isEmpty() -> R.string.media_convert_lyrics_container_unsupported
            isAudioOutput && audioLyricsPlainText -> R.string.media_convert_lyrics_plain_text_hint
            isAudioOutput && maxSubtitleFiles == 1 -> R.string.media_convert_lyrics_single_tag_hint
            isAudioOutput -> R.string.media_convert_lyrics_hint
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
        if (singleSource && !sourceIsAudio) {
            Text(stringResource(R.string.media_convert_source_subtitles), style = MaterialTheme.typography.titleSmall)
            when {
                sourceSubtitleProbeInProgress -> Text(
                    stringResource(R.string.media_convert_source_subtitle_probe_reading),
                    modifier = Modifier.testTag("convert-source-subtitle-probe-reading"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                sourceSubtitleProbeFailed -> Text(
                    stringResource(R.string.media_convert_source_subtitle_probe_failed),
                    modifier = Modifier.testTag("convert-source-subtitle-probe-error"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
                sourceSubtitleTracks.isEmpty() -> Text(
                    stringResource(R.string.media_convert_source_subtitle_empty),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            sourceSubtitleTracks.forEachIndexed { index, track ->
                Column(Modifier.fillMaxWidth().testTag("convert-source-subtitle-${track.streamIndex}")) {
                    Text(track.title.ifBlank { stringResource(R.string.media_convert_source_subtitle_track, index + 1) },
                        style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(track.language.takeIf { it.isNotBlank() }, track.codec,
                        stringResource(R.string.media_convert_source_subtitle_default).takeIf { track.isDefault }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(when {
                        !outputFormatSelected -> R.string.media_convert_source_subtitle_output_pending
                        sourceSubtitleSupport.getOrNull(index) == true -> R.string.media_convert_source_subtitle_preserved
                        !isAudioOutput && type == MediaConvertSubtitleType.BURN_IN -> R.string.media_convert_source_subtitle_burn_omitted
                        else -> R.string.media_convert_source_subtitle_unsupported
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (singleSource && sourceIsAudio) {
            Text(stringResource(R.string.media_convert_source_lyrics), style = MaterialTheme.typography.titleSmall)
            when {
                sourceSubtitleProbeInProgress -> Text(stringResource(R.string.media_convert_source_lyrics_reading),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                sourceLyricsProbeFailed -> Text(stringResource(R.string.media_convert_source_lyrics_failed),
                    modifier = Modifier.testTag("convert-source-lyrics-error"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                sourceEmbeddedLyrics.isEmpty() -> Text(stringResource(R.string.media_convert_source_lyrics_empty),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sourceEmbeddedLyrics.forEachIndexed { index, lyric ->
                Column(Modifier.fillMaxWidth().testTag("convert-source-lyric-$index")) {
                    Text(lyric.title.ifBlank { stringResource(R.string.media_convert_source_lyric_number, index + 1) },
                        style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                        Text(listOfNotNull(lyric.field, lyric.language.takeIf { it.isNotBlank() },
                            stringResource(if (lyric.synced) R.string.media_convert_source_lyrics_timed else R.string.media_convert_source_lyrics_plain))
                            .joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f, fill = false),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.media_convert_source_lyrics_view),
                            modifier = Modifier.testTag("convert-source-lyric-view-$index")
                                .clickable(role = Role.Button) { onViewEmbeddedLyric(index) },
                            style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.primary))
                    }
                }
            }
        }
    }
}
