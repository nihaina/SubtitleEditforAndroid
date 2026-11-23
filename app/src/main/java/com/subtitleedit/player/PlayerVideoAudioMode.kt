package com.subtitleedit.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

@Composable
internal fun PlayerVideoAudioMode(
    subtitleText: String,
    fontSize: Int,
    lineSpacing: Int,
    alignment: PlayerSubtitleAlignment,
    onShowLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val noteSize = minOf(120.dp, maxHeight * 0.48f, maxWidth * 0.4f)
        val noteTop = (maxHeight - noteSize) * 0.4f
        PlayerVideoAudioNote(
            Modifier.align(Alignment.TopCenter).padding(top = noteTop).size(noteSize)
                .testTag("player-video-audio-note").clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.player_lyrics_show),
                    onClick = onShowLyrics
                )
        )
        if (subtitleText.isNotBlank()) {
            Text(
                text = subtitleText,
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp,
                    top = noteTop + noteSize + 8.dp).testTag("player-video-audio-subtitle"),
                color = PlayerColors.OnDark,
                style = playerAudioSubtitleTextStyle(fontSize, lineSpacing, alignment),
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun PlayerVideoAudioNote(modifier: Modifier = Modifier) {
    Box(
        modifier.background(PlayerColors.Surface, CircleShape)
            .border(1.dp, PlayerColors.OnDarkVariant.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.55f),
            tint = PlayerColors.OnDark
        )
    }
}
