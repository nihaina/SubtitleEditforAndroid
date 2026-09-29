package com.subtitleedit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier
) {
    if (state.mediaType == EditorMediaType.SUBTITLE_ONLY) return
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Slider(
            value = state.progress,
            onValueChange = {
                onSeekStarted()
                onSeekProgress(it)
            },
            onValueChangeFinished = onSeekFinished,
            enabled = state.enabled,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onTogglePlayPause, enabled = state.enabled) {
                Icon(
                    painter = painterResource(
                        if (state.isPlaying) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play
                    ),
                    contentDescription = if (state.isPlaying) "暂停" else "播放"
                )
            }
            Text(
                text = if (state.mediaType == EditorMediaType.VIDEO) {
                    state.videoTimeText
                } else {
                    "${state.currentTimeText} / ${state.totalTimeText}"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = state.playbackSpeedLabel,
                modifier = Modifier
                    .clickable(enabled = state.enabled, onClick = onSpeedClick)
                    .padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (state.mediaType == EditorMediaType.VIDEO) {
                IconButton(onClick = onToggleFullscreen, enabled = state.enabled) {
                    Icon(
                        painter = painterResource(
                            if (isFullscreen) R.drawable.ic_video_fullscreen_exit
                            else R.drawable.ic_video_fullscreen
                        ),
                        contentDescription = if (isFullscreen) "退出全屏" else "全屏播放"
                    )
                }
            }
            if (state.videoStatus != null) {
                Text(
                    text = state.videoStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
