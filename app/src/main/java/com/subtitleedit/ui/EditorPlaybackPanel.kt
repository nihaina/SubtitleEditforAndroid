package com.subtitleedit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.subtitleedit.R
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.editor.EditorPlaybackUiState

/** Material 3 playback controls shared by audio and video editor modes. */
@Composable
internal fun EditorPlaybackPanel(
    state: EditorPlaybackUiState,
    onTogglePlayPause: () -> Unit,
    onSeekStarted: () -> Unit,
    onSeekProgress: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSpeedClick: () -> Unit,
    onToggleFullscreen: () -> Unit = {},
    isFullscreen: Boolean = false,
    overlay: Boolean = false,
    modifier: Modifier = Modifier
) {
    val speedDescription = stringResource(R.string.activity_editor_contentdescription_09)
    val playPauseDescription = when (state.mediaType) {
        EditorMediaType.VIDEO -> stringResource(
            if (state.isPlaying) R.string.editor_video_pause else R.string.editor_video_play
        )
        else -> stringResource(R.string.activity_editor_contentdescription_08)
    }
    if (state.mediaType == EditorMediaType.SUBTITLE_ONLY ||
        (state.mediaType == EditorMediaType.VIDEO && !state.videoControlsVisible)
    ) return
    if (state.mediaType == EditorMediaType.VIDEO) {
        // Keep the legacy overlay order: time at the top, controls at the bottom.
        Column(
            modifier = modifier
                .fillMaxWidth()
                // The legacy video controls row used #26000000 (about 15% black).
                // Keep the same contrast so the media remains visible under the controls.
                .then(if (overlay) Modifier.background(Color.Black.copy(alpha = 0.15f)) else Modifier)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                // The legacy LinearLayout only had 4dp start padding; its children had no
                // inter-item spacing. Keep the seek track and 48dp controls at the same x
                // positions as the XML layout.
                horizontalArrangement = Arrangement.Start
            ) {
                if (state.enabled) {
                    IconButton(onClick = onTogglePlayPause) {
                        Icon(
                            painter = painterResource(if (state.isPlaying) R.drawable.ic_video_pause else R.drawable.ic_video_play),
                            contentDescription = playPauseDescription,
                            tint = Color.White
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Slider(
                    value = state.progress,
                    onValueChange = {
                        onSeekStarted()
                        onSeekProgress(it)
                    },
                    onValueChangeFinished = onSeekFinished,
                    enabled = state.enabled,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                )
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(48.dp)
                        .clickable(enabled = state.enabled, onClick = onSpeedClick),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.playbackSpeedLabel,
                        modifier = Modifier.semantics { contentDescription = speedDescription },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, letterSpacing = 0.sp),
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                IconButton(onClick = onToggleFullscreen, enabled = state.enabled) {
                    Icon(
                        painter = painterResource(if (isFullscreen) R.drawable.ic_video_fullscreen_exit else R.drawable.ic_video_fullscreen),
                        contentDescription = stringResource(
                            if (isFullscreen) R.string.editor_video_exit_fullscreen
                            else R.string.editor_video_fullscreen
                        ),
                        tint = Color.White
                    )
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Slider(
                value = state.progress,
                onValueChange = {
                    onSeekStarted()
                    onSeekProgress(it)
                },
                onValueChangeFinished = onSeekFinished,
                enabled = state.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // activity_editor.xml gives this row a 2dp top margin below the SeekBar.
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable(enabled = state.enabled, role = Role.Button, onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                        contentDescription = playPauseDescription,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = state.playbackSpeedLabel,
                    modifier = Modifier
                        .semantics { contentDescription = speedDescription }
                        .clickable(enabled = state.enabled, onClick = onSpeedClick)
                        .background(Color(0xFF2C2C2C), RoundedCornerShape(3.dp))
                        .border(1.dp, Color(0xFF444444), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(state.currentTimeText, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text(state.totalTimeText, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
